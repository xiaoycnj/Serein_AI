package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ChatFeatureCheck {
    public static void main(String[] args) throws Exception {
        versions();
        profileAndPayload();
        routing();
        checkpoints();
    }

    private static void versions() throws Exception {
        ChatHistory history = ChatHistory.read(new JSONArray("""
                [{"prompt":"original","reply":"first"}, {"prompt":"follow-up","reply":"second"}]
                """), -1);
        equal(history.path(history.leaf()), List.of(0, 1));
        equal(history.turn(0).model(), "Serein");
        history.append(history.parent(0), new ChatHistory.Turn("original", "regenerated", "New model"));
        history.append(history.parent(0), new ChatHistory.Turn("edited", "new answer", "New model"));
        equal(history.variants(3), List.of(0, 2, 3));
        equal(history.path(history.leaf()), List.of(3));
        equal(history.path(history.latestDescendant(0)), List.of(0, 1));
        equal(history.path(history.latestDescendant(2)), List.of(2));
        history.select(history.latestDescendant(0));
        ChatHistory restored = ChatHistory.read(new JSONArray(history.write().toString()), history.leaf());
        equal(restored.leaf(), 1);
        equal(restored.turn(1).prompt(), "follow-up");
        equal(restored.turn(2).reply(), "regenerated");
        equal(restored.turn(3).prompt(), "edited");
        restored.append(1, new ChatHistory.Turn("unsaved", "unsaved", "model"));
        restored.rollbackAppend(1);
        equal(restored.leaf(), 1);
        equal(restored.write().length(), 4);
        System.out.println("PASS: legacy history and non-destructive edit/regenerate versions round trip");
    }

    private static void profileAndPayload() throws Exception {
        AccountProfile legacy = AccountProfile.read(null, "Local nickname");
        equal(legacy.name(), "Local nickname");
        equal(legacy.systemPrompt(), "");
        AccountProfile profile = new AccountProfile("Lin", "Hi\n\"quoted\"", true);
        equal(AccountProfile.read(profile.encode(), ""), profile);
        var model = new Providers.Model("model", "effort", "", "", "");
        JSONArray privateMessages = ModelApi.payload(model, List.of(), "question", 0, legacy.systemPrompt(), "")
                .getJSONArray("messages");
        equal(privateMessages.length(), 1);
        equal(privateMessages.getJSONObject(0).getString("role"), "user");
        JSONObject request = ModelApi.payload(model, List.of(new ChatHistory.Turn("recent", "answer", "model")),
                "question", 2, profile.systemPrompt(), "older context");
        JSONArray messages = request.getJSONArray("messages");
        equal(messages.getJSONObject(0).getString("role"), "system");
        equal(messages.getJSONObject(0).getString("content"), profile.systemPrompt());
        equal(messages.getJSONObject(1).getString("role"), "user");
        equal(messages.getJSONObject(3).getString("content"), "recent");
        equal(messages.getJSONObject(5).getString("content"), "question");
        equal(request.getString("reasoning_effort"), "medium");
        System.out.println("PASS: profile remains private by default and authorized requests preserve message order");
    }

    private static void routing() {
        var fast = new ChatContext.ModelSpec("fast", 16_000, 0, 1.0);
        var other = new ChatContext.ModelSpec("other-standard", 16_000, 0, 2.0);
        var advanced = new ChatContext.ModelSpec("advanced", 32_000, 1, 4.0);
        var flagship = new ChatContext.ModelSpec("flagship", 128_000, 2, 10.0);
        var unknown = new ChatContext.ModelSpec("unknown", 0, 0, 0.0);
        var candidates = List.of(other, flagship, advanced, fast, unknown);
        equal(ChatContext.route(candidates, "hello", 1_000, 1_000, false), fast);
        equal(ChatContext.route(candidates, "review this code", 1_000, 1_000, false), advanced);
        equal(ChatContext.route(candidates, "compare the architecture", 1_000, 1_000, false), flagship);
        equal(ChatContext.route(candidates, "hello", 11_000, 2_000, true), advanced);
        equal(ChatContext.route(List.of(fast, unknown), "hello", 11_000, 2_000, false), null);
        equal(ChatContext.route(List.of(fast, unknown), "hello", 11_000, 2_000, true), fast);
        System.out.println("PASS: route ranking checks task tier, capacity, compaction setting and known price");
    }

    private static void checkpoints() throws Exception {
        String older = "\u4e2d".repeat(8000) + "\uD83D\uDC4B".repeat(3000) + "end of history";
        List<ChatHistory.Turn> history = new ArrayList<>();
        history.add(new ChatHistory.Turn("older question", older, "model"));
        for (int i = 0; i < 4; i++) history.add(new ChatHistory.Turn("recent " + i, "answer " + i, "model"));
        List<Integer> path = List.of(0, 2, 3, 4, 5);
        List<String> fragments = new ArrayList<>();
        ChatContext.Summarizer summarizer = (prev, fragment) -> {
            equal(prev, fragments.isEmpty() ? "" : "summary");
            if (Character.isHighSurrogate(fragment.charAt(fragment.length() - 1))) throw new AssertionError("Split surrogate pair");
            fragments.add(fragment);
            return "summary";
        };
        var result = ChatContext.prepare(history, path, "profile", "next", 16_000, "provider/model", null, summarizer);
        equal(String.join("", fragments), "\nUser: older question\nAssistant: " + older);
        equal(result.recent(), history.subList(1, 5));
        equal(result.checkpoint().through(), 0);
        var reused = ChatContext.prepare(history, path, "", "next", 16_000, "provider/model", result.checkpoint(),
                (prev, fragment) -> { throw new AssertionError("Duplicate summary call"); });
        equal(reused.summary(), "summary");
        fragments.clear();
        ChatContext.prepare(history, List.of(1, 2, 3, 4, 5), "", "next", 16_000, "provider/model", result.checkpoint(), summarizer);
        equal(fragments.isEmpty(), false);
        fragments.clear();
        ChatContext.prepare(history, path, "", "next", 16_000, "different/model", result.checkpoint(), summarizer);
        equal(fragments.isEmpty(), false);
        try {
            ChatContext.prepare(history, path, "", "next", 16_000, "provider/model", null, (prev, fragment) -> "");
            throw new AssertionError("Empty summary accepted");
        } catch (IOException expected) {
            equal(history.size(), 5);
        }
        System.out.println("PASS: chunked summaries retain recent turns and validate branch and model scope before reuse");
    }

    private static void equal(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
