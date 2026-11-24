package app.serein.chat;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Chat-only adaptations of AstraAgent rich-chat.js and its Markdown export (MIT).
final class ChatArtifacts {
    private static final int MAX_TABLE_CELLS = 8192;
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(StrikethroughExtension.create(), TablesExtension.builder().maxCells(MAX_TABLE_CELLS).build()))
            .build();
    private static final Parser WITHOUT_TABLES = Parser.builder()
            .extensions(List.of(StrikethroughExtension.create())).build();
    private static final int BLOCK_LIMIT = 100;
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(Map.entry("javascript", "js"),
            Map.entry("typescript", "ts"), Map.entry("python", "py"), Map.entry("kotlin", "kt"),
            Map.entry("c++", "cpp"), Map.entry("rust", "rs"), Map.entry("markdown", "md"),
            Map.entry("yaml", "yaml"), Map.entry("ruby", "rb"));
    private static final Set<String> SOURCE_SUFFIXES = Set.of("js", "ts", "py", "kt", "java", "c", "cpp", "h",
            "rs", "go", "cs", "rb", "swift", "lua", "sql", "json", "xml", "yaml", "yml", "css", "md", "txt");

    sealed interface Block permits CodeBlock, Table {}
    record CodeBlock(String language, String source) implements Block {}
    record Table(List<List<String>> rows) implements Block {
        int columns() { return rows.get(0).size(); }
    }
    record Blocks(List<Block> items, boolean limited) {}
    record Quote(String value, boolean truncated) {}

    @FunctionalInterface
    interface Content { void write(Writer out) throws IOException; }

    record Document(String fileName, String mime, Content content) {}

    static Node parse(String markdown) {
        try {
            return PARSER.parse(markdown);
        } catch (IllegalArgumentException limit) {
            // GFM pads short rows to the full header width, even when the source is small.
            return WITHOUT_TABLES.parse(markdown);
        }
    }

    static Blocks blocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        collect(parse(markdown), blocks, 0);
        boolean limited = blocks.size() > BLOCK_LIMIT;
        return new Blocks(List.copyOf(blocks.subList(0, Math.min(blocks.size(), BLOCK_LIMIT))), limited);
    }

    private static void collect(Node parent, List<Block> blocks, int depth) {
        if (depth > 96) return;
        for (Node node = parent.getFirstChild(); node != null && blocks.size() <= BLOCK_LIMIT; node = node.getNext()) {
            if (node instanceof FencedCodeBlock fenced) {
                String info = fenced.getInfo().strip();
                int end = 0;
                while (end < info.length() && !Character.isWhitespace(info.charAt(end))) end++;
                blocks.add(new CodeBlock(info.substring(0, boundary(info, Math.min(32, end))), fenced.getLiteral()));
            } else if (node instanceof IndentedCodeBlock indented) {
                blocks.add(new CodeBlock("", indented.getLiteral()));
            } else if (node instanceof TableBlock) {
                List<List<String>> rows = new ArrayList<>();
                for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
                    for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                        if (!(row instanceof TableRow)) continue;
                        List<String> cells = new ArrayList<>();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (!(cell instanceof TableCell)) continue;
                            StringBuilder text = new StringBuilder();
                            cellText(cell, text, 0);
                            cells.add(text.toString());
                        }
                        if (!cells.isEmpty()) rows.add(List.copyOf(cells));
                    }
                }
                if (!rows.isEmpty()) blocks.add(new Table(List.copyOf(rows)));
            } else collect(node, blocks, depth + 1);
        }
    }

    private static void cellText(Node parent, StringBuilder text, int depth) {
        if (depth > 96) return;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text literal) text.append(literal.getLiteral());
            else if (node instanceof Code inline) text.append(inline.getLiteral());
            else if (node instanceof HtmlInline html) text.append(html.getLiteral());
            else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) text.append('\n');
            else cellText(node, text, depth + 1);
        }
    }

    static Quote quote(String draft, String source, int limit) {
        if (source.isBlank()) return null;
        String separator = draft.isEmpty() ? "" : "\n\n";
        int available = limit - draft.length() - separator.length() - 2;
        if (available < 4) return null;
        int inputEnd = boundary(source, Math.min(source.length(), available));
        String normalized = source.substring(0, inputEnd).replace("\r\n", "\n").replace('\r', '\n');
        String quoted = "> " + normalized.replace("\n", "\n> ");
        boolean truncated = inputEnd < source.length() || quoted.length() > available;
        if (truncated) {
            int end = boundary(quoted, Math.min(quoted.length(), available - 1));
            quoted = quoted.substring(0, end).stripTrailing() + "…";
        }
        return new Quote(draft + separator + quoted + "\n\n", truncated);
    }

    static int boundary(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    static Document codeFile(CodeBlock block) {
        String language = block.language().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.getOrDefault(language, language);
        return new Document("snippet." + (SOURCE_SUFFIXES.contains(extension) ? extension : "txt"),
                "text/plain", out -> out.write(block.source()));
    }

    static Document replyFile(String markdown) {
        return new Document("serein-reply.md", "text/markdown", out -> out.write(markdown));
    }

    static Document csvFile(Table table) {
        return new Document("table.csv", "text/csv", out -> {
            out.write('\ufeff');
            writeTable(out, table, ',');
        });
    }

    static String tableClipboard(Table table) {
        StringWriter out = new StringWriter();
        try { writeTable(out, table, '\t'); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
        return out.toString();
    }

    private static void writeTable(Writer out, Table table, char separator) throws IOException {
        for (int row = 0; row < table.rows().size(); row++) {
            if (row > 0) out.write("\r\n");
            List<String> cells = table.rows().get(row);
            for (int column = 0; column < table.columns(); column++) {
                if (column > 0) out.write(separator);
                String value = cellForSpreadsheet(column < cells.size() ? cells.get(column) : "");
                if (separator == ',' || value.indexOf(separator) >= 0 || value.indexOf('"') >= 0
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    out.write('"');
                    out.write(value.replace("\"", "\"\""));
                    out.write('"');
                } else out.write(value);
            }
        }
    }

    private static String cellForSpreadsheet(String value) {
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if (cp == '\t' || cp == '\r' || cp == '\n') return "'" + value;
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)
                    || Character.getType(cp) == Character.FORMAT) continue;
            String normalized = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
            return !normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0)) >= 0 ? "'" + value : value;
        }
        return value;
    }

    static Document conversation(ChatStore.Chat chat) {
        String title = chat.title;
        var history = chat.history;
        List<ChatHistory.Turn> turns = new ArrayList<>();
        for (int id : history.path(history.leaf())) turns.add(history.turn(id));
        return new Document("serein-chat.md", "text/markdown", out -> {
            out.write("# " + heading(title) + "\n");
            for (ChatHistory.Turn turn : turns) {
                out.write("\n## 你\n\n");
                writeMessage(out, turn.prompt());
                out.write("\n\n## " + heading(turn.model()) + "\n\n");
                if (!turn.reply().isBlank() || turn.card() == null) writeMessage(out, turn.reply());
                if (turn.card() != null) {
                    if (!turn.reply().isBlank()) out.write("\n\n");
                    out.write(turn.card().transcript());
                }
                switch (turn.replyState()) {
                    case STOPPED -> out.write("\n\n> 已停止生成");
                    case INTERRUPTED -> out.write("\n\n> 回复未完成");
                }
                out.write("\n");
            }
        });
    }

    private static void writeMessage(Writer out, String markdown) throws IOException {
        out.write(markdown);
        out.write(closingFence(markdown));
    }

    static String closingFence(String markdown) {
        // A streamed reply may end inside a top-level fence; keep later turns outside it.
        if (!markdown.contains("~~~") && !markdown.contains("```")) return "";
        if (parse(markdown).getLastChild() instanceof FencedCodeBlock fence && fence.getClosingFenceLength() == null) {
            return "\n" + fence.getFenceCharacter().repeat(fence.getOpeningFenceLength());
        }
        return "";
    }

    private static String heading(String value) {
        return value.replaceAll("\\R", " ").replace("\\", "\\\\")
                .replace("#", "\\#").replace("<", "\\<").replace(">", "\\>");
    }
}
