package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class ChatToolsCheck {
    private static final ChatTools.Options BOTH = new ChatTools.Options(true, true);
    private static final String SEARCH_RUN = openAiCall(ChatTools.SEARCH, "{\"query\":\"rain tomorrow\"}");
    private static final String TEXT_RUN = completion("\"delta\":{\"content\":\"Done.\"},\"finish_reason\":\"stop\"");

    public static void main(String[] args) throws Exception {
        var ordinaryCount = new int[1];
        var ordinaryText = new StringBuilder();
        ModelApi.chatWithTools(body(), false, true, BOTH, ChatToolsCheck::unexpected, new ModelApi.Cancellation(),
                ordinaryText::append, (request, delta, call) -> {
                    ordinaryCount[0]++;
                    equal(request.getJSONArray("tools").length(), 3);
                    read(TEXT_RUN, false, delta, call);
                });
        equal(ordinaryCount[0], 1);
        equal(ordinaryText.toString(), "Done.");

        var requests = new int[1];
        var calls = new int[1];
        var searched = new StringBuilder();
        String sources = ChatTools.searchResult(List.of(new WebSearch.Hit("https://example.com/weather", "Weather", "Rain")));
        ModelApi.chatWithTools(body(), false, true, BOTH, (name, argument) -> {
            calls[0]++;
            equal(name, ChatTools.SEARCH);
            equal(argument, "rain tomorrow");
            return sources;
        }, new ModelApi.Cancellation(), searched::append, (request, delta, call) -> {
            if (++requests[0] == 1) read(SEARCH_RUN, false, delta, call);
            else {
                equal(request.getString("tool_choice"), "none");
                var messages = request.getJSONArray("messages");
                var assistant = messages.getJSONObject(1);
                equal(assistant.getString("reasoning_content"), "internal");
                equal(assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"), "call_1");
                var result = messages.getJSONObject(2);
                equal(result.getString("role"), "tool");
                equal(result.getString("tool_call_id"), "call_1");
                equal(result.getString("content"), sources);
                read(TEXT_RUN, false, delta, call);
            }
        });
        equal(calls[0], 1);
        equal(requests[0], 2);
        equal(searched.toString(), "Checking.\n\nDone.");

        requests[0] = 0;
        var generated = new StringBuilder();
        String prompt = "Rain over a \"quiet\" garden\nWide shot";
        String input = new JSONObject().put("prompt", prompt).toString();
        ModelApi.chatWithTools(body(), true, false, BOTH, (name, argument) -> {
            equal(name, ChatTools.IMAGE);
            equal(argument, prompt);
            return "Image shown inline.";
        }, new ModelApi.Cancellation(), generated::append, (request, delta, call) -> {
            if (++requests[0] == 1) {
                equal(request.getJSONArray("tools").getJSONObject(1).getString("name"), ChatTools.IMAGE);
                read(an("content_block_start", "\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"generate_image\",\"input\":{}}")
                        + anDelta(input.substring(0, 12)) + anDelta(input.substring(12))
                        + an("content_block_stop", "\"index\":1")
                        + an("message_delta", "\"delta\":{\"stop_reason\":\"tool_use\"}")
                        + event("{\"type\":\"message_stop\"}"), true, delta, call);
            } else {
                equal(request.getJSONObject("tool_choice").getString("type"), "none");
                var messages = request.getJSONArray("messages");
                var use = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0);
                equal(use.getJSONObject("input").getString("prompt"), prompt);
                var result = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0);
                equal(result.getString("tool_use_id"), "toolu_1");
                equal(result.getString("content"), "Image shown inline.");
                ModelApi.acceptJson(new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"Here it is.\"}],\"stop_reason\":\"end_turn\"}"), true, delta, call);
            }
        });
        equal(requests[0], 2);
        equal(generated.toString(), "Here it is.");

        requests[0] = 0;
        String card = "{\"title\":\"Plan\",\"blocks\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"Hello\"}]}";
        var result = ModelApi.chatWithTools(body(), false, true, BOTH, ChatToolsCheck::unexpected,
                new ModelApi.Cancellation(), ignored -> {}, (request, delta, call) -> {
                    requests[0]++;
                    read(openAiCall(ChatCard.TOOL, card), false, delta, call);
                });
        equal(result.title(), "Plan");
        equal(requests[0], 1);

        expectIo(() -> exchange(SEARCH_RUN, new ChatTools.Options(false, true), new ModelApi.Cancellation()));
        expectIo(() -> exchange(SEARCH_RUN.replace("call_1", ""), BOTH, new ModelApi.Cancellation()));
        expectIo(() -> exchange(SEARCH_RUN.replace("tool_calls\"}", "stop\"}"), BOTH, new ModelApi.Cancellation()));
        expectIo(() -> exchange(openAiCall(ChatTools.SEARCH, "{\"query\":\"a\",\"extra\":true}"), BOTH, new ModelApi.Cancellation()));
        expectIo(() -> exchange(openAiCall(ChatTools.IMAGE, "{\"prompt\":\"" + "a".repeat(4001) + "\"}"), BOTH, new ModelApi.Cancellation()));
        var cancelled = new ModelApi.Cancellation();
        cancelled.cancel();
        expectIo(() -> exchange(SEARCH_RUN, BOTH, cancelled));
        var stoppedDuringTool = new ModelApi.Cancellation();
        requests[0] = 0;
        expectIo(() -> ModelApi.chatWithTools(body(), false, false, BOTH, (name, argument) -> {
            stoppedDuringTool.cancel();
            return "cancelled";
        }, stoppedDuringTool, ignored -> {}, (request, delta, call) -> {
            requests[0]++;
            read(SEARCH_RUN, false, delta, call);
        }));
        equal(requests[0], 1);
        System.out.println("PASS: chat-only tools, OpenAI and Anthropic streaming, result continuation, IUI coexistence and cancellation");
    }

    private static void exchange(String reply, ChatTools.Options options, ModelApi.Cancellation cancellation) throws Exception {
        ModelApi.chatWithTools(body(), false, false, options, ChatToolsCheck::unexpected, cancellation, ignored -> {},
                (request, delta, call) -> read(reply, false, delta, call));
    }

    private static String unexpected(String name, String arg) { throw new AssertionError("Unexpected tool " + name); }

    private static JSONObject body() throws Exception {
        return new JSONObject().put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Question")));
    }

    private static String openAiCall(String name, String input) {
        int split = input.length() / 2;
        return completion("\"delta\":{\"content\":\"Checking.\",\"reasoning_content\":\"internal\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":" + JSONObject.quote(name)
                + ",\"arguments\":" + JSONObject.quote(input.substring(0, split)) + "}}]}")
                + completion("\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":" + JSONObject.quote(input.substring(split)) + "}}]}")
                + completion("\"finish_reason\":\"tool_calls\"");
    }

    private static String an(String type, String fields) { return event("{\"type\":" + JSONObject.quote(type) + "," + fields + "}"); }
    private static String anDelta(String part) {
        return an("content_block_delta", "\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + JSONObject.quote(part) + "}");
    }
    private static String completion(String fields) { return event("{\"choices\":[{" + fields + "}]}"); }
    private static String event(String payload) { return "data: " + payload + "\n\n"; }

    private static void read(String sse, boolean anthropic, Consumer<String> delta, IuiCall call) throws IOException {
        ModelApi.readStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), new ModelApi.Cancellation(), delta, anthropic, call);
    }

    private static void equal(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void expectIo(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }
}
