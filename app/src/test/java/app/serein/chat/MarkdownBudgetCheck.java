package app.serein.chat;

import java.io.StringWriter;

final class MarkdownBudgetCheck {
    public static void main(String[] args) throws Exception {
        require(!MarkdownBudget.requiresPreview("## Heading\n\n**Bold**, *emphasis* and `code`."));
        require(MarkdownBudget.requiresPreview("**x** ".repeat(600)));
        require(MarkdownBudget.requiresPreview("a\n".repeat(600)));
        require(MarkdownBudget.requiresPreview("A".repeat(25_000)));

        String denselyStyled = "**Strong** *emphasis* `literal` [link](https://example.com)\n\n".repeat(18_000);
        String original = "FIRST\n\n" + denselyStyled + "\n\nFixture complete.";
        String preview = MarkdownBudget.preview(original);
        require(preview.startsWith("FIRST"));
        require(preview.contains("\n\n…\n\n"));
        require(preview.endsWith("Fixture complete."));
        require(preview.length() < 8200 && preview.chars().filter(c -> c == '\n').count() <= 164);

        String emoji = "a".repeat(6143) + "\ud83d\ude00" + "z".repeat(30_000) + "\ud83d\ude00" + "b".repeat(2047);
        String cut = MarkdownBudget.preview(emoji);
        require(cut.codePoints().noneMatch(cp -> cp >= 0xd800 && cp <= 0xdfff));
        require(MarkdownBudget.preview("complete").equals("complete"));

        var export = new StringWriter();
        ChatArtifacts.replyFile(original).content().write(export);
        require(export.toString().equals(original));
        System.out.println("PASS: bounded Markdown display preserves Unicode edges and full raw export");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Markdown preview invariant failed");
    }
}
