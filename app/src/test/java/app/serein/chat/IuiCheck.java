package app.serein.chat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class IuiCheck {
    private static final String CARD = """
            {"title":"Plan","purpose":"question","blocks":[
              {"id":"time","type":"metrics","items":[{"label":"Minutes","value":15}]},
              {"id":"steps","type":"checklist","items":[{"id":"break","label":"Take a break"}]},
              {"id":"pick","type":"choices","items":[{"id":"now","label":"Start now"}]}
            ]}
            """;

    public static void main(String[] args) throws Exception {
        ChatCard card = ChatCard.parse(CARD);
        equal(ChatCard.read(card.write()), card);
        equal(ChatCard.tools(false).getJSONObject(0).getJSONObject("function").getString("name"), "render_iui");
        equal(ChatCard.tools(true).getJSONObject(0).has("input_schema"), true);
        for (String invalid : List.of(CARD + " true", CARD.replace("question", "confirmation"),
                CARD.replace("choices", "form"), CARD.replace("\"pick\"", "\"steps\""),
                CARD.replace("\"Plan\"", "null"), CARD.replace("\"label\":\"Start now\"", "\"html\":\"<script>\""),
                "[".repeat(1000), " ".repeat(ChatCard.MAX_ARGUMENTS + 1))) {
            try { ChatCard.parse(invalid); throw new AssertionError("Invalid card accepted"); }
            catch (JSONException expected) { }
        }

        String start = completion("""
                "delta":{"content":"Here is a plan.","tool_calls":[
                  {"index":0,"type":"function","function":{"name":"render_iui","arguments":%s}}]}
                """.formatted(JSONObject.quote(CARD.substring(0, 26))));
        String tail = completion("""
                "delta":{"tool_calls":[{"index":0,"function":{"arguments":%s}}]}
                """.formatted(JSONObject.quote(CARD.substring(26))));
        String finish = completion("\"finish_reason\":\"tool_calls\"");
        var text = new ArrayList<String>();
        IuiCall openai = new IuiCall();
        read(start + tail + finish, false, openai, text);
        equal(openai.result(), card);
        equal(text, List.of("Here is a plan."));
        ioFailure(() -> read(start + tail + finish, false, null, new ArrayList<>()));
        ioFailure(() -> read(start + tail, false, new IuiCall(), new ArrayList<>()));
        IuiCall noFinish = new IuiCall();
        read(start + tail + "data: [DONE]\n\n", false, noFinish, new ArrayList<>());
        ioFailure(noFinish::result);
        IuiCall invalidName = new IuiCall();
        read(start.replace("render_iui", "unknown") + tail + finish, false, invalidName, new ArrayList<>());
        ioFailure(invalidName::result);
        ioFailure(() -> read(start + tail.replace("[{\"index\":0,\"function\"", "[{\"index\":1,\"function\"") + finish, false,
                new IuiCall(), new ArrayList<>()));
        IuiCall nativeStream = new IuiCall();
        read(event("""
                {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","name":"render_iui","input":{}}}
                """) + event("""
                {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":%s}}
                """.formatted(JSONObject.quote(CARD)))
                + event("{\"type\":\"content_block_stop\",\"index\":2}")
                + event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}")
                + event("{\"type\":\"message_stop\"}"), true, nativeStream, new ArrayList<>());
        equal(nativeStream.result(), card);
        IuiCall nativeJson = new IuiCall();
        ModelApi.acceptJson(new JSONObject("""
                {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"render_iui","input":%s}]}
                """.formatted(CARD)), true, ignored -> {}, nativeJson);
        equal(nativeJson.result(), card);
        System.out.println("PASS: validated IUI schema, bounded tool calls and fragmented streams");

        var disk = new AtomicReference<>("{\"history\":[],\"active\":\"\"}");
        ChatStore store = new ChatStore(disk.get(), disk::set);
        store.append(-1, "Question", "", "Serein", null, card).commit();
        var original = store.active;
        store.updateCard(original, 0, "steps/break", true).commit();
        store.updateCard(original, 0, "pick", "now").commit();
        Map<String, Object> state = original.history.turn(0).cardState();
        equal(card.draft(state), "Plan\n- Take a break\n- Start now");
        var model = new Providers.Model("test", "none", "", "", "");
        String prior = ModelApi.payload(model, List.of(original.history.turn(0)), "Next", 0, "", "")
                .getJSONArray("messages").getJSONObject(1).getString("content");
        equal(prior, card.transcript());
        equal(prior.contains("steps/break"), false);
        store.append(-1, "Question", "", "Serein", null, card).commit();
        equal(original.history.turn(1).cardState(), Map.of());
        try {
            store.updateCard(original, 0, "pick", "now").commit();
            throw new AssertionError("Modified a hidden version");
        } catch (IllegalStateException expected) { }
        store.selectVersion(0).commit();
        ChatStore restored = new ChatStore(disk.get(), disk::set);
        equal(restored.active.history.turn(0).cardState(), state);
        ChatStore failing = new ChatStore(disk.get(), ignored -> { throw new IllegalStateException("write failure"); });
        try {
            failing.updateCard(failing.active, 0, "steps/break", false).commit();
            throw new AssertionError("Swallowed write failure");
        } catch (IllegalStateException expected) {
            equal(failing.active.history.turn(0).cardState(), state);
        }
        restored.delete(restored.active).commit();
        equal(new ChatStore(disk.get(), disk::set).recent.size(), 0);
        System.out.println("PASS: local card state restores, rolls back and stays isolated by reply version");
    }

    private static String completion(String fields) {
        return event("{\"choices\":[{\"index\":0," + fields + "}]}");
    }

    private static String event(String object) { return "data: " + object.replace("\n", " ") + "\n\n"; }

    private static void read(String body, boolean anthropic, IuiCall call, List<String> text) throws IOException {
        ModelApi.readStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                new ModelApi.Cancellation(), text::add, anthropic, call);
    }

    @FunctionalInterface private interface Attempt { void run() throws IOException; }

    private static void ioFailure(Attempt attempt) throws IOException {
        try { attempt.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected an IOException");
    }

    private static void equal(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
