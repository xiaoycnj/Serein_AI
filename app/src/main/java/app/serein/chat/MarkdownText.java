package app.serein.chat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.*;

import java.net.URI;

final class MarkdownText {
    record Rendered(SpannableStringBuilder text, boolean hasArtifacts) {}

    static Rendered render(String markdown, int ink, int secondary, int code, int inset) {
        Renderer renderer = new Renderer(ink, secondary, code, inset);
        renderer.children(ChatArtifacts.parse(markdown), 0);
        var text = renderer.text;
        while (text.length() != 0 && text.charAt(text.length() - 1) == '\n') {
            text.delete(text.length() - 1, text.length());
        }
        // Preserve content made entirely of Markdown syntax or reference definitions.
        return new Rendered(text.length() == 0 ? new SpannableStringBuilder(markdown) : text, renderer.hasArtifacts);
    }

    private static final class Renderer {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        final int ink, secondary, code, inset;
        boolean hasArtifacts;

        Renderer(int ink, int secondary, int code, int inset) {
            this.ink = ink;
            this.secondary = secondary;
            this.code = code;
            this.inset = inset;
        }

        void children(Node node, int depth) {
            if (depth > 96) return;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) append(child, depth + 1);
        }

        void append(Node node, int depth) {
            int start = text.length();
            if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof TableBlock) {
                hasArtifacts = true;
            }
            if (node instanceof Text literal) text.append(literal.getLiteral());
            else if (node instanceof Code inline) {
                text.append(inline.getLiteral());
                code(start);
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) text.append('\n');
            else if (node instanceof FencedCodeBlock fenced) blockCode(fenced.getLiteral());
            else if (node instanceof IndentedCodeBlock indented) blockCode(indented.getLiteral());
            else if (node instanceof HtmlInline html) text.append(html.getLiteral());
            else if (node instanceof HtmlBlock html) {
                text.append(html.getLiteral().stripTrailing());
                lines(2);
            } else if (node instanceof Heading heading) {
                children(node, depth);
                style(new StyleSpan(Typeface.BOLD), start);
                style(new RelativeSizeSpan(heading.getLevel() == 1 ? 1.375f : heading.getLevel() == 2 ? 1.25f : 1.125f), start);
                lines(2);
            } else if (node instanceof Emphasis) {
                children(node, depth);
                style(new StyleSpan(Typeface.ITALIC), start);
            } else if (node instanceof StrongEmphasis) {
                children(node, depth);
                style(new StyleSpan(Typeface.BOLD), start);
            } else if (node instanceof Strikethrough) {
                children(node, depth);
                style(new StrikethroughSpan(), start);
            } else if (node instanceof Link link) {
                children(node, depth);
                if (safeLink(link.getDestination())) style(new URLSpan(link.getDestination()), start);
            } else if (node instanceof Image) {
                children(node, depth);
            } else if (node instanceof Paragraph) {
                children(node, depth);
                lines(node.getParent() instanceof ListItem ? 1 : 2);
            } else if (node instanceof BlockQuote) {
                children(node, depth);
                style(new QuoteSpan(secondary, Math.max(1, inset / 4), inset), start);
                lines(2);
            } else if (node instanceof ListBlock list) {
                list(list, depth);
            } else if (node instanceof TableRow) {
                children(node, depth);
                lines(node.getParent().getNext() == null && node.getNext() == null ? 2 : 1);
            } else if (node instanceof TableCell cell) {
                children(node, depth);
                if (cell.isHeader()) {
                    style(new StyleSpan(Typeface.BOLD), start);
                    style(new BackgroundColorSpan(code), start);
                }
                if (node.getNext() != null) {
                    int divider = text.length();
                    text.append("  │  ");
                    style(new ForegroundColorSpan(secondary), divider);
                }
            } else if (node instanceof ThematicBreak) {
                text.append("──────");
                style(new ForegroundColorSpan(secondary), start);
                lines(2);
            } else {
                children(node, depth);
            }
        }

        void list(ListBlock list, int depth) {
            int number = list instanceof OrderedList ordered ? ordered.getMarkerStartNumber() : 0;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                int start = text.length();
                boolean nested = list.getParent() instanceof ListItem;
                text.append(list instanceof OrderedList ? number++ + ". " : "•  ");
                children(item, depth + 1);
                lines(1);
                style(new LeadingMarginSpan.Standard(nested ? inset : 0, nested ? 2 * inset : inset), start);
            }
            if (!(list.getParent() instanceof ListItem)) lines(2);
        }

        void blockCode(String literal) {
            int start = text.length();
            text.append(literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal);
            code(start);
            lines(2);
        }

        void code(int start) {
            style(new TypefaceSpan("monospace"), start);
            style(new RelativeSizeSpan(.92f), start);
            style(new ForegroundColorSpan(ink), start);
            style(new BackgroundColorSpan(code), start);
        }

        void style(Object span, int start) {
            if (start < text.length()) text.setSpan(span, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        void lines(int count) {
            for (int at = text.length() - 1; count > 0; at--, count--) {
                if (at < 0 || text.charAt(at) != '\n') {
                    while (count-- > 0) text.append('\n');
                    return;
                }
            }
        }
    }

    static boolean safeLink(String destination) {
        try {
            URI uri = URI.create(destination);
            return uri.getHost() != null && uri.getRawUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
