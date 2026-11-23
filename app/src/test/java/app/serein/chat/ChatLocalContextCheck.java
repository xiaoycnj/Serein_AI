package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.kxml2.io.KXmlParser;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ChatLocalContextCheck {
    private static final ChatFile DOCUMENT = new ChatFile("a".repeat(32), "meeting.txt", "text/plain", 80, 30);
    private static final ChatFile IMAGE = new ChatFile("b".repeat(32), "photo.png", "image/png", 3, 0);
    private static final ChatFile LIBRARY = new ChatFile("c".repeat(32), "project.md", "text/plain", 80, 30);
    private static final String SCOPE = "service\nhttps://example.test\nvision";

    public static void main(String[] args) throws Exception {
        attachments();
        documents();
        memories();
        toolTurns();
        System.out.println("PASS: attachment payloads and history, confined document retrieval, local memories and bounded tool turns");
    }

    private static void attachments() throws Exception {
        String documentText = "会议安排：周三下午。";
        ChatAttachments.Source read = file -> file.image() ? new byte[]{1, 2, 3} : documentText.getBytes(StandardCharsets.UTF_8);
        var prepared = ChatAttachments.prepare(List.of(), "", List.of(DOCUMENT, IMAGE), read, false, 1024,
                SCOPE, new ModelApi.Cancellation());
        var model = new Providers.Model("vision", "none", "", "", "");
        JSONArray content = ModelApi.payload(model, List.of(), "", 0, "", "", false, prepared)
                .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        require(content.getJSONObject(0).getString("text").contains(documentText), "documents ground attachment-only messages");
        require(content.getJSONObject(1).getJSONObject("image_url").getString("url").equals("data:image/png;base64,AQID"),
                "OpenAI-compatible data URLs");

        var turn = new ChatHistory.Turn("", "answered", "vision", null, Map.of(), ChatHistory.ReplyState.COMPLETE,
                null, null, List.of(DOCUMENT, IMAGE), List.of(LIBRARY), SCOPE);
        var same = ChatAttachments.prepare(List.of(turn), "follow-up", List.of(), read, true, 1024,
                SCOPE, new ModelApi.Cancellation());
        JSONObject source = ((JSONArray) same.history().get(0)).getJSONObject(1).getJSONObject("source");
        require(source.getString("media_type").equals("image/png") && source.getString("data").equals("AQID"),
                "Anthropic images replay as base64 within the original scope");
        var switched = ChatAttachments.prepare(List.of(turn), "follow-up", List.of(), read, false, 1024,
                "other service", new ModelApi.Cancellation());
        require(switched.history().get(0) instanceof String, "switched models receive no historical image bytes");
        require(!ChatAttachments.available(List.of(turn), List.of(), "other service").contains(IMAGE),
                "switched models reserve no history image payload");

        var trimmed = ChatAttachments.prepare(List.of(), "read", List.of(DOCUMENT), file -> new byte[0],
                false, 0, SCOPE, new ModelApi.Cancellation());
        require(trimmed.prompt().toString().contains("\"omitted\":true"), "size limits are explicit in the payload");
        expectIo(() -> ChatAttachments.prepare(List.of(), "read", List.of(DOCUMENT), file -> {
            throw new FileNotFoundException();
        }, false, 1024, SCOPE, new ModelApi.Cancellation()));

        var disk = new AtomicReference<>("{\"history\":[],\"active\":\"\"}");
        var store = new ChatStore(disk.get(), disk::set);
        store.append(-1, turn.prompt(), turn.reply(), turn.model(), null, null, turn.replyState(), null, null,
                turn.attachments(), turn.references(), turn.imageScope());
        var original = store.active;
        store.fork(original, 0);
        store.delete(original);
        var restored = new ChatStore(disk.get(), disk::set);
        require(restored.active.history.turn(0).equals(turn), "a chat branch retains the shared attachment references on reload");
        var ids = new HashSet<String>();
        restored.active.history.collectFileIds(ids);
        require(ids.equals(Set.of(DOCUMENT.id(), IMAGE.id(), LIBRARY.id())), "cleanup sees all live file references");
        var retry = new ReplyRetry(restored.active, 0, "read", true, null, List.of(DOCUMENT));
        require(retry.consumesDraft("read", List.of(DOCUMENT)) && !retry.consumesDraft("read", List.of(IMAGE)),
                "retry keeps a newer attachment draft intact");
    }

    private static void documents() throws Exception {
        require(FileText.extract("test.csv", "\ufeffa,b\n1,2".getBytes(StandardCharsets.UTF_8)).equals("a,b\n1,2"), "UTF-8 BOM");
        require(FileText.extract("test.md", "标题".getBytes(StandardCharsets.UTF_16)).equals("标题"), "UTF-16 BOM");
        require(FileText.extract("test.txt", "一份资料".getBytes(Charset.forName("GB18030"))).equals("一份资料"), "GB18030 text");
        expectIo(() -> FileText.extract("test.txt", new byte[]{0, 1, 2}));
        expectIo(() -> FileText.extract("test.docx", new byte[]{0, 1, 2}));
        expectIo(() -> FileText.extract("test.txt", "a".repeat(60_001).getBytes(StandardCharsets.UTF_8)));
        String body = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>项目 &amp; 日期</w:t><w:tab/><w:t>11 月 23 日</w:t></w:r></w:p>
                  <w:p><w:r><w:t>第二段</w:t><w:br/><w:t>下一行</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        require(FileText.extract("资料.docx", docx(body), KXmlParser::new).equals("项目 & 日期\t11 月 23 日\n第二段\n下一行"),
                "DOCX body preserves entities, tabs, paragraphs, and line breaks");
        expectIo(() -> FileText.extract("资料.docx", docx("<!DOCTYPE w:document>\n" + body), KXmlParser::new));

        String longText = "ﬃ".repeat(2000) + "会议日期：12 月 1 日。" + "余".repeat(500);
        Set<ChatFile> allowed = new HashSet<>(Set.of(DOCUMENT, LIBRARY));
        var cancellation = new ModelApi.Cancellation();
        var refs = new ChatReferences(List.of(DOCUMENT, IMAGE), List.of(LIBRARY), file -> {
            if (!allowed.contains(file)) throw new FileNotFoundException();
            return file.equals(LIBRARY) ? longText : "会议主题：发布计划。";
        }, cancellation);
        require(new JSONObject(refs.read(LIBRARY.id())).has("error"), "library IDs require same-turn search approval");
        require(new JSONObject(refs.read(IMAGE.id())).has("error"), "file tools cannot return image bytes");
        var hit = new JSONObject(refs.search("会议日期")).getJSONArray("matches").getJSONObject(0);
        require(hit.getString("file_id").equals(LIBRARY.id()) && hit.getString("excerpt").contains("会议日期"),
                "keyword search locates Chinese content after normalization changes offsets");
        var passage = new JSONObject(refs.read(LIBRARY.id()));
        require(hit.getString("citation").equals(passage.getString("citation")), "search and read retain the same citation");
        require(passage.getString("excerpt").length() <= 3204 && refs.citations().get(0).equals(LIBRARY), "bounded citations");
        allowed.remove(LIBRARY);
        require(new JSONObject(refs.read(LIBRARY.id())).has("error"), "a removed file cannot be read with a previous result ID");
        cancellation.cancel();
        expectIo(() -> refs.search("会议"));

        var older = new ChatHistory.Turn("question", "answer", "model", null, Map.of(),
                ChatHistory.ReplyState.COMPLETE, null, null, List.of(DOCUMENT), List.of(LIBRARY));
        List<ChatHistory.Turn> longHistory = new ArrayList<>();
        longHistory.add(older);
        for (int i = 0; i < 5; i++) longHistory.add(new ChatHistory.Turn("question", "answer", "model"));
        require(ChatAttachments.available(longHistory, List.of(), SCOPE).isEmpty()
                && ChatAttachments.searchableDocuments(longHistory, List.of()).equals(List.of(DOCUMENT)),
                "older attachments remain searchable without replaying bodies or exposing prior library citations as attachments");
    }

    private static byte[] docx(String body) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void memories() {
        var disk = new AtomicReference<>("[]");
        var memory = new ChatMemory(disk.get(), disk::set);
        String note = "我喜欢简短的回答";
        require(memory.assess(note, "请记住：" + note) == ChatMemory.Outcome.SAVED && disk.get().equals("[]"),
                "assessing a pending note does not persist it");
        require(memory.remember("喜欢超长回答", note) == ChatMemory.Outcome.UNSUITABLE, "model inventions are rejected");
        require(memory.remember("密码是 123456", "密码是 123456") == ChatMemory.Outcome.UNSUITABLE, "credential-like content is rejected");
        require(memory.remember("fixtureSecret", "我的密码是 fixtureSecret") == ChatMemory.Outcome.UNSUITABLE,
                "a note cannot omit the credential label to pass the check");
        require(memory.remember(note, note) == ChatMemory.Outcome.SAVED, "an eligible note persists");
        require(memory.remember(note, note) == ChatMemory.Outcome.ALREADY_SAVED, "notes deduplicate");
        var restored = new ChatMemory(disk.get(), disk::set);
        require(restored.search("回答偏好").size() == 1 && restored.search("无关内容").isEmpty(), "recall finds relevant notes only");
        restored.clear();
        require(disk.get().equals("[]") && restored.entries().isEmpty(), "clear releases all entries");
    }

    private static void toolTurns() throws Exception {
        var requests = new AtomicInteger();
        var actions = new AtomicInteger();
        var options = new ChatTools.Options(true, true, true, true, true);
        var body = new JSONObject().put("messages", new JSONArray());
        var output = new StringBuilder();
        ModelApi.chatWithTools(body, false, false, options, (name, value) -> {
            actions.incrementAndGet();
            return "fixture tool result";
        }, new ModelApi.Cancellation(), output::append, (request, delta, call) -> {
            int index = requests.incrementAndGet();
            if (index == 1) {
                invoke(call, ChatTools.REMEMBER, "content", "我喜欢简短的回答", index);
            } else if (index < 5) {
                require(request.getJSONArray("tools").length() == 3, "local calls remove external tools and duplicate writes");
                invoke(call, ChatTools.FIND_FILE, "query", "会议", index);
            } else {
                require(request.getString("tool_choice").equals("none"), "the fifth request only produces the answer");
                delta.accept("回答");
            }
        });
        require(requests.get() == 5 && actions.get() == 4 && output.toString().equals("回答"), "one turn has a four-call budget");

        var cancellation = new ModelApi.Cancellation();
        requests.set(0);
        try {
            ModelApi.chatWithTools(new JSONObject().put("messages", new JSONArray()), false, false, options,
                    (name, value) -> { cancellation.cancel(); return "cancelled"; }, cancellation, ignored -> {},
                    (request, delta, call) -> invoke(call, ChatTools.RECALL, "query", "偏好", requests.incrementAndGet()));
            throw new AssertionError("Cancelled recall continued");
        } catch (InterruptedIOException expected) {
            require(requests.get() == 1, "cancelling discards local results before the next model request");
        }
    }

    private static void invoke(IuiCall call, String name, String key, String value, int number) throws IOException, org.json.JSONException {
        var function = new JSONObject().put("name", name).put("arguments", new JSONObject().put(key, value).toString());
        var event = new JSONObject().put("id", "local_" + number).put("function", function);
        IuiCall.completions(call, new JSONObject().put("tool_calls", new JSONArray().put(event)));
        IuiCall.finish(call);
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void expectIo(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
