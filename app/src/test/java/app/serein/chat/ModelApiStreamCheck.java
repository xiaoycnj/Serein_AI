package app.serein.chat;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ModelApiStreamCheck {
    private static final String PREFIX = "data: {\"choices\":[{\"index\":0,";
    private static final String FIRST = PREFIX + "\"delta\":{\"content\":\"\u4f60\"}}]}\n\n";
    private static final String DONE = "data: [DONE]\n\n";

    public static void main(String[] args) throws Exception {
        List<String> chunks = new ArrayList<>();
        String mixed = "\ufeff: keep-alive\r\n\r\n"
                + "event: ping\rdata: heartbeat\r\r"
                + "id: 4\r\ndata: {\r\ndata: \"choices\":[{\"index\":1,\"delta\":{\"content\":\"other\"}},\r\n"
                + "data: {\"index\":0,\"delta\":{\"reasoning_content\":\"ignored\"}}]}\r\n\r\n"
                + "data\n\n" + FIRST
                + PREFIX + "\"delta\":{\"content\":null}}]}\n\n"
                + PREFIX + "\"delta\":{\"content\":\"\u597d\uD83D\uDC4B\"}}]}\r\r"
                + "data: {\"choices\":[],\"usage\":{}}\n\n" + DONE + "data: ignored\n\n";
        ModelApi.readStream(input(mixed, 1), new ModelApi.Cancellation(), chunks::add);
        expect(chunks, List.of("\u4f60", "\u597d\uD83D\uDC4B"));
        System.out.println("PASS: fragmented UTF-8/SSE, primary choice, and answer-only output");

        chunks.clear();
        ModelApi.readStream(input(FIRST + PREFIX + "\"finish_reason\":\"stop\"}]}\n\n", 100),
                new ModelApi.Cancellation(), chunks::add);
        expect(chunks, List.of("\u4f60"));
        System.out.println("PASS: finish_reason can end a stream without [DONE]");

        for (String suffix : List.of("", DONE.stripTrailing(), "event: error\ndata: problem\n\n",
                "data: {\"error\":{\"message\":\"upstream error\"}}\n\n", "data: {\n\n",
                PREFIX + "\"finish_reason\":\"length\"}]}\n\n",
                PREFIX + "\"finish_reason\":\"tool_calls\"}]}\n\n",
                PREFIX + "\"finish_reason\":\"insufficient_system_resource\"}]}\n\n",
                PREFIX + "\"finish_reason\":\"aborted\"}]}\n\n")) {
            chunks.clear();
            ioFailure(() -> ModelApi.readStream(input(FIRST + suffix, 9), new ModelApi.Cancellation(), chunks::add));
            expect(chunks, List.of("\u4f60"));
        }
        System.out.println("PASS: incomplete, error, malformed and truncated streams retain earlier deltas");

        ModelApi.Cancellation cancellation = new ModelApi.Cancellation();
        chunks.clear();
        IOException cancelled = ioFailure(() -> ModelApi.readStream(input(FIRST + FIRST + DONE, 1024), cancellation, text -> {
            chunks.add(text);
            cancellation.cancel();
        }));
        if (!(cancelled instanceof InterruptedIOException)) throw new AssertionError(cancelled);
        expect(chunks, List.of("\u4f60"));
        System.out.println("PASS: cancellation stops dispatching already-buffered events");

        chunks.clear();
        String oversized = FIRST + ": " + "x".repeat(2 * 1024 * 1024) + "\n\n" + DONE;
        ioFailure(() -> ModelApi.readStream(input(oversized, 8192), new ModelApi.Cancellation(), chunks::add));
        expect(chunks, List.of("\u4f60"));
        System.out.println("PASS: response byte budget limits oversized wire payloads");

        chunks.clear();
        ModelApi.readStream(input(PREFIX + "\"delta\":{\"refusal\":\"declined\"}}]}\n\n" + DONE, 3),
                new ModelApi.Cancellation(), chunks::add);
        expect(chunks, List.of("declined"));
        System.out.println("PASS: display user-facing Chat Completions refusal text");

        anthropicStreams();
    }

    private static void anthropicStreams() throws Exception {
        List<String> chunks = new ArrayList<>();
        String blocks = named("message_start", "\"message\":{}")
                + ": heartbeat\n\n" + named("ping", "\"usage\":{}")
                + named("content_block_start", "\"content_block\":{\"type\":\"text\",\"text\":\"Hello\"}")
                + named("content_block_delta", "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private\"}")
                + named("future_event", "\"text\":\"ignored\"")
                + named("content_block_delta", "\"delta\":{\"type\":\"text_delta\",\"text\":\" \uD83D\uDC4B\"}");
        String beforeStop = blocks + named("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}");
        String stop = named("message_stop", "\"usage\":{}");
        ModelApi.readStream(input(beforeStop + stop, 1), new ModelApi.Cancellation(), chunks::add, true);
        expect(chunks, List.of("Hello", " \uD83D\uDC4B"));
        for (String truncated : List.of(beforeStop, beforeStop + stop.stripTrailing(), beforeStop + DONE,
                blocks + named("message_delta", "\"delta\":{\"stop_reason\":\"max_tokens\"}") + stop,
                blocks + named("error", "\"error\":{\"type\":\"overloaded_error\",\"message\":\"private details\"}"))) {
            chunks.clear();
            IOException failure = ioFailure(() -> ModelApi.readStream(input(truncated, 5),
                    new ModelApi.Cancellation(), chunks::add, true));
            expect(chunks, List.of("Hello", " \uD83D\uDC4B"));
            if (truncated.contains("overloaded_error") && !failure.getMessage().equals("服务商暂时繁忙，请稍后重试。")) {
                throw new AssertionError(failure);
            }
        }
        chunks.clear();
        ModelApi.acceptJson(new JSONObject("""
                {"type":"message","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"hidden"},
                 {"type":"text","text":"one"},{"type":"text","text":"two"}]}
                """), true, chunks::add);
        expect(chunks, List.of("one", "two"));
        System.out.println("PASS: Anthropic text blocks, terminal event, errors, and JSON reply");
    }

    private static String named(String type, String fields) {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\"," + fields + "}\n\n";
    }

    private static ByteArrayInputStream input(String text, int chunkSize) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, chunkSize));
            }
        };
    }

    private static void expect(List<String> actual, List<String> expected) {
        if (!actual.equals(expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    @FunctionalInterface
    private interface ReadAttempt { void run() throws Exception; }

    private static IOException ioFailure(ReadAttempt attempt) throws Exception {
        try { attempt.run(); }
        catch (IOException expected) { return expected; }
        throw new AssertionError("Expected an IOException");
    }
}
