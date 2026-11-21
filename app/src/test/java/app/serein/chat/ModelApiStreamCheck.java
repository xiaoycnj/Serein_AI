package app.serein.chat;

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
                PREFIX + "\"finish_reason\":\"tool_calls\"}]}\n\n")) {
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
