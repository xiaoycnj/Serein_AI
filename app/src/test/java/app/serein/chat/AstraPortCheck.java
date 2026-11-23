package app.serein.chat;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class AstraPortCheck {
    private static final String EMPTY = "{\"history\":[],\"active\":\"\"}";

    public static void main(String[] args) throws Exception {
        archiveAndFork();
        search();
        quote();
        artifacts();
        oversizedTable();
        markdownExport();
    }

    private static void archiveAndFork() throws Exception {
        var disk = new AtomicReference<>(EMPTY);
        var writable = new AtomicBoolean(true);
        var store = new ChatStore(disk.get(), saved -> {
            if (!writable.get()) throw new IllegalStateException("test storage failure");
            disk.set(saved);
        });
        ChatCard card = ChatCard.parse("""
                {"title":"Options","blocks":[{"id":"tasks","type":"checklist","items":[{"id":"one","label":"First"}]}]}
                """);
        store.append(-1, "Original", "", "model", new ChatContext.Checkpoint("provider", 0, "old summary"), card);
        ChatStore.Chat source = store.active;
        store.updateCard(source, 0, "tasks/one", true);
        store.append(0, "Second", "partial", "model", null, null, ChatHistory.ReplyState.STOPPED);
        store.append(1, "Later", "not copied", "model", null);
        store.append(0, "Other version", "not copied", "model", null);
        store.selectVersion(1);
        store.pin(source, true);

        writable.set(false);
        failure(() -> store.archive(source, true));
        equal(store.active, source);
        equal(source.archived, false);
        failure(() -> store.fork(source, 1));
        equal(store.active, source);
        equal(store.recent, List.of(source));
        writable.set(true);

        failure(() -> store.fork(source, 3));
        store.fork(source, 1);
        ChatStore.Chat branch = store.active;
        equal(branch.id.equals(source.id), false);
        equal(branch.pinned, false);
        equal(branch.archived, false);
        equal(branch.checkpoint, null);
        equal(branch.history.write().length(), 2);
        equal(source.history.path(source.history.leaf()), List.of(0, 1, 2));
        equal(branch.history.turn(1).replyState(), ChatHistory.ReplyState.STOPPED);
        equal(branch.history.turn(0).cardState(), Map.of("tasks/one", true));
        store.updateCard(branch, 0, "tasks/one", false);
        equal(source.history.turn(0).cardState(), Map.of("tasks/one", true));

        store.archive(source, true);
        equal(store.active, branch);
        store.archive(branch, true);
        equal(store.active, null);
        ChatStore restored = new ChatStore(disk.get(), disk::set);
        ChatStore.Chat original = restored.recent.get(0);
        equal(original.id, source.id);
        equal(original.pinned, true);
        equal(original.archived, true);
        equal(restored.recent.size(), 2);
        restored.open(original);
        equal(original.archived, true);
        restored.archive(original, false);
        equal(original.archived, false);
        equal(original.history.write().length(), 4);
        equal(original.history.turn(0).cardState(), Map.of("tasks/one", true));
        equal(new ChatStore(disk.get(), disk::set).active.archived, false);
        System.out.println("PASS: archive restore round trips, fork copies only the chosen path, IUI state is independent, and failed writes roll back");
    }

    private static void search() {
        ChatStore store = new ChatStore(EMPTY, ignored -> {});
        store.append(-1, "first question", "雨后 report", "model", null);
        ChatStore.Chat chat = store.active;
        store.rename(chat, "ＢＥＴＡ sessions");
        store.append(0, "visible phrase", "Emoji " + "\ud83d\udc4b".repeat(50) + " can be found", "model", null);
        store.append(0, "hidden variant", "private version", "model", null);
        store.selectVersion(1);

        var snapshot = ChatSearch.snapshot(store.recent);
        var hit = ChatSearch.find(snapshot, "ｂｅｔａ　雨后", false).hits().get(0);
        equal(hit.source().chat(), chat);
        equal(hit.id(), 0);
        equal(hit.answer(), true);
        equal(hit.excerpt(), "雨后 report");
        equal(ChatSearch.find(snapshot, "sessions", false).hits().get(0).id(), -1);
        equal(ChatSearch.find(snapshot, "visible", false).hits().get(0).answer(), false);
        equal(ChatSearch.find(snapshot, "report visible", false).hits().size(), 1);
        equal(ChatSearch.find(snapshot, "hidden variant", false).hits().isEmpty(), true);
        equal(ChatSearch.find(snapshot, " ", false).hits().isEmpty(), true);
        String excerpt = ChatSearch.find(snapshot, "can be found", false).hits().get(0).excerpt();
        equal(excerpt.contains("can be found"), true);
        equal(wellFormed(excerpt), true);

        store.archive(chat, true);
        snapshot = ChatSearch.snapshot(store.recent);
        equal(ChatSearch.find(snapshot, "report", false).hits().isEmpty(), true);
        equal(ChatSearch.find(snapshot, "report", true).hits().get(0).source().archived(), true);
        store.delete(chat);
        equal(ChatSearch.find(ChatSearch.snapshot(store.recent), "report", true).hits().isEmpty(), true);

        List<ChatSearch.Source> sources = new ArrayList<>();
        for (int i = 0; i <= ChatSearch.RESULT_LIMIT; i++) {
            sources.add(new ChatSearch.Source(chat, "report " + i, false, List.of()));
        }
        var limited = ChatSearch.find(sources, "report", false);
        equal(limited.hits().size(), ChatSearch.RESULT_LIMIT);
        equal(limited.limited(), true);
        Thread.currentThread().interrupt();
        try { equal(ChatSearch.find(sources, "report", true).hits().isEmpty(), true); }
        finally { Thread.interrupted(); }
        System.out.println("PASS: Unicode search locates prompts/replies, excludes other versions and deleted chats, and filters archives");
    }

    private static void quote() {
        var quote = ChatArtifacts.quote("现有草稿", "第一行\r\n第二行", 100);
        equal(quote.value(), "现有草稿\n\n> 第一行\n> 第二行\n\n");
        equal(quote.truncated(), false);
        var shortQuote = ChatArtifacts.quote("draft", "\ud83d\udc4b\n".repeat(40), 22);
        equal(shortQuote.value().startsWith("draft\n\n> "), true);
        equal(shortQuote.value().length() <= 22, true);
        equal(wellFormed(shortQuote.value()), true);
        equal(shortQuote.truncated(), true);
        equal(ChatArtifacts.quote("can't truncate this draft", "reply", 8), null);
        System.out.println("PASS: quoting keeps drafts, preserves line prefixes, and respects the Unicode-safe composer limit");
    }

    private static void artifacts() throws Exception {
        var blocks = ChatArtifacts.blocks("""
                A paragraph.

                ~~~JavaScript option
                const message = "hello";
                ~~~

                ~~~
                no language
                ~~~

                    indented block

                | Name | Result |
                | --- | --- |
                | **Label**, "quoted" | ＝2 |
                | [Link](https://example.com) | 4 |
                """);
        equal(blocks.items().size(), 4);
        equal(blocks.limited(), false);
        var code = (ChatArtifacts.CodeBlock) blocks.items().get(0);
        equal(code.language(), "JavaScript");
        equal(((ChatArtifacts.CodeBlock) blocks.items().get(1)).language(), "");
        equal(((ChatArtifacts.CodeBlock) blocks.items().get(2)).source(), "indented block\n");
        equal(ChatArtifacts.codeFile(code).fileName(), "snippet.js");
        equal(write(ChatArtifacts.codeFile(code)), "const message = \"hello\";\n");
        equal(ChatArtifacts.codeFile(new ChatArtifacts.CodeBlock("../../active.html", "text")).fileName(), "snippet.txt");
        equal(ChatArtifacts.codeFile(new ChatArtifacts.CodeBlock("html", "<script></script>")).fileName(), "snippet.txt");

        var table = (ChatArtifacts.Table) blocks.items().get(3);
        equal(table.rows(), List.of(List.of("Name", "Result"), List.of("Label, \"quoted\"", "＝2"), List.of("Link", "4")));
        equal(ChatArtifacts.tableClipboard(table), "Name\tResult\r\n\"Label, \"\"quoted\"\"\"\t'＝2\r\nLink\t4");
        equal(write(ChatArtifacts.csvFile(table)), "\ufeff\"Name\",\"Result\"\r\n\"Label, \"\"quoted\"\"\",\"'＝2\"\r\n\"Link\",\"4\"");
        var sensitiveTable = new ChatArtifacts.Table(List.of(List.of(" \u200b=1", "+2", "-3", "@4", "\tleading", "x\ny")));
        equal(write(ChatArtifacts.csvFile(sensitiveTable)),
                "\ufeff\"' \u200b=1\",\"'+2\",\"'-3\",\"'@4\",\"'\tleading\",\"x\ny\"");
        equal(ChatArtifacts.blocks("~~~\nx\n~~~\n\n".repeat(105)).limited(), true);
        System.out.println("PASS: shared Markdown parser extracts fenced/indented code and GFM tables; file and spreadsheet serializers are bounded and escaped");
    }

    private static void markdownExport() throws Exception {
        ChatStore store = new ChatStore(EMPTY, ignored -> {});
        ChatCard card = ChatCard.parse("""
                {"title":"Public card","blocks":[{"id":"internal","type":"choices","items":[{"id":"secret","label":"Visible label"}]}]}
                """);
        store.append(-1, "visible prompt", "visible answer", "<model>", new ChatContext.Checkpoint("scope", 0, "hidden summary"), card);
        ChatStore.Chat chat = store.active;
        store.rename(chat, "Safe # title");
        store.updateCard(chat, 0, "internal", "secret");
        store.append(0, "another visible prompt", "fragment", "Serein", null, null, ChatHistory.ReplyState.INTERRUPTED);
        store.append(0, "hidden version", "should not be included", "Serein", null);
        store.selectVersion(1);
        var file = ChatArtifacts.conversation(chat);
        store.selectVersion(2);
        String markdown = write(file);
        equal(markdown.startsWith("# Safe \\# title\n"), true);
        equal(markdown.contains("## \\<model\\>"), true);
        equal(markdown.contains("visible prompt"), true);
        equal(markdown.contains("Public card"), true);
        equal(markdown.contains("Visible label"), true);
        equal(markdown.contains("回复未完成"), true);
        for (String hidden : List.of("hidden version", "hidden summary", "internal", "secret", chat.id)) {
            equal(markdown.contains(hidden), false);
        }
        var truncated = new ChatStore(EMPTY, ignored -> {});
        truncated.append(-1, "code", "~~~~js\npartial();", "model", null, null, ChatHistory.ReplyState.STOPPED);
        truncated.append(0, "Next", "~~~\ncomplete();\n~~~", "model", null);
        String interrupted = write(ChatArtifacts.conversation(truncated.active));
        equal(interrupted.contains("partial();\n~~~~\n\n> 已停止生成\n\n## 你\n\nNext"), true);
        equal(interrupted.contains("complete();\n~~~\n"), true);
        equal(ChatArtifacts.blocks(interrupted).items().size(), 2);
        System.out.println("PASS: Markdown export snapshots the selected path, separates interrupted code, and excludes private metadata");
    }

    private static void oversizedTable() throws Exception {
        String sparseTable = "|Column".repeat(120) + "|\n" + "|---".repeat(120) + "|\n"
                + "|short row|\n".repeat(300);
        String reply = sparseTable + "\n```text\nCode after the table.\n```\n\nReply complete.";
        var blocks = ChatArtifacts.blocks(reply);
        equal(blocks.items(), List.of(new ChatArtifacts.CodeBlock("text", "Code after the table.\n")));
        var store = new ChatStore(EMPTY, ignored -> {});
        store.append(-1, "Table result", reply, "model", null);
        equal(store.active.history.turn(0).reply(), reply);
        equal(write(ChatArtifacts.conversation(store.active)).contains(reply), true);
        System.out.println("PASS: oversized sparse tables skip GFM expansion and full Markdown is preserved");
    }

    private static String write(ChatArtifacts.Document document) throws Exception {
        StringWriter text = new StringWriter();
        document.content().write(text);
        return text.toString();
    }

    private static boolean wellFormed(String value) {
        return value.codePoints().noneMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff);
    }

    private static void failure(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected an IllegalStateException");
    }

    private static void equal(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
