package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ChatFeatureCheck {
    public static void main(String[] args) throws Exception {
        versions();
        replyRecovery();
        conversationManagement();
        profileAndPayload();
        routing();
        checkpoints();
    }

    private static void replyRecovery() throws Exception {
        var empty = "{\"history\":[],\"active\":\"\"}";
        var disk = new java.util.concurrent.atomic.AtomicReference<>(empty);
        var store = new ChatStore(empty, disk::set);
        var first = new ReplyRetry(null, -1, "failed prompt", true);
        equal(first.matches(store.active), true);
        equal(first.consumesDraft("failed prompt"), true);
        equal(first.consumesDraft("a new draft"), false);

        store.append(-1, "question", "complete", "model", null);
        var chat = store.active;
        equal(first.matches(chat), false);
        var edited = new ReplyRetry(chat, -1, "edited prompt", false);
        equal(edited.matches(chat), true);
        equal(edited.parent(), -1);
        equal(edited.consumesDraft("edited prompt"), false);
        store.append(0, "follow-up", "received", "model", null, null, ChatHistory.ReplyState.STOPPED);
        var retry = new ReplyRetry(chat, 0, "replacement", true);
        equal(edited.matches(chat), false);
        equal(retry.matches(chat), true);
        store.append(0, "follow-up", "another fragment", "model", null, null, ChatHistory.ReplyState.INTERRUPTED);
        equal(retry.matches(chat), false);

        var loaded = new ChatStore(disk.get(), disk::set);
        equal(loaded.active.history.turn(0).replyState(), ChatHistory.ReplyState.COMPLETE);
        equal(loaded.active.history.turn(1).replyState(), ChatHistory.ReplyState.STOPPED);
        equal(loaded.active.history.turn(2).replyState(), ChatHistory.ReplyState.INTERRUPTED);
        equal(loaded.active.history.variants(2), List.of(1, 2));
        loaded.selectVersion(1);
        equal(loaded.active.history.leaf(), 1);
        equal(loaded.active.history.turn(1).reply(), "received");
        equal(loaded.active.history.turn(1).assistantText(), "received");
        equal(loaded.active.history.write().getJSONObject(0).has("replyState"), false);
        equal(retry.matches(loaded.active), false);
        loaded.open(null);
        equal(retry.matches(loaded.active), false);

        var failing = new ChatStore(disk.get(), ignored -> { throw new IllegalStateException("write failure"); });
        try {
            failing.append(-1, "unsaved", "partial", "model", null, null, ChatHistory.ReplyState.INTERRUPTED);
            throw new AssertionError("Expected append failure");
        } catch (IllegalStateException expected) {
            equal(failing.active, null);
            equal(failing.recent.size(), 1);
        }
        System.out.println("PASS: partial replies persist by version and retries keep newer drafts and other chats separate");
    }

    private static void conversationManagement() throws Exception {
        var disk = new java.util.concurrent.atomic.AtomicReference<>("{\"history\":[],\"active\":\"\"}");
        ChatStore store = new ChatStore(disk.get(), disk::set);
        var checkpoint = new ChatContext.Checkpoint("provider/model", 0, "old summary");
        store.append(-1, "first", "answer", "model", checkpoint);
        store.append(-1, "first", "another answer", "model", null);
        ChatStore.Chat first = store.active;
        store.open(null);
        store.append(-1, "second", "another chat", "model", null);
        ChatStore.Chat second = store.active;
        long originalTime = first.updatedAt;
        store.rename(first, "  Renamed\nchat  ");
        equal(first.updatedAt, originalTime);
        equal(store.recent, List.of(second, first));

        ChatStore reloaded = new ChatStore(disk.get(), disk::set);
        ChatStore.Chat renamed = reloaded.recent.get(1);
        equal(renamed.id, first.id);
        equal(renamed.title, "Renamed chat");
        equal(renamed.history.variants(1), List.of(0, 1));
        equal(renamed.checkpoint, checkpoint);
        equal(reloaded.active.id, second.id);
        reloaded.pin(renamed, true);
        reloaded.append(reloaded.active.history.leaf(), "second follow-up", "recent answer", "model", null);
        equal(reloaded.recent.get(0), renamed);
        equal(reloaded.recent.get(1), reloaded.active);
        ChatStore pinnedReload = new ChatStore(disk.get(), disk::set);
        equal(pinnedReload.recent.get(0).title, "Renamed chat");
        equal(pinnedReload.recent.get(0).pinned, true);
        pinnedReload.pin(pinnedReload.recent.get(0), false);
        equal(pinnedReload.recent.get(0).title, "second");
        equal(pinnedReload.recent.get(0).pinned, false);
        try {
            reloaded.rename(renamed, " \n ");
            throw new AssertionError("Blank title accepted");
        } catch (IllegalArgumentException expected) {
            equal(renamed.title, "Renamed chat");
        }

        ChatStore failing = new ChatStore(disk.get(), saved -> { throw new IllegalStateException("test write failure"); });
        ChatStore.Chat current = failing.active;
        List<ChatStore.Chat> before = List.copyOf(failing.recent);
        for (Runnable mutation : List.<Runnable>of(() -> failing.rename(current, "Unsaved"),
                () -> failing.pin(current, true), () -> failing.delete(current))) {
            try {
                mutation.run();
                throw new AssertionError("Write failure was swallowed");
            } catch (IllegalStateException expected) {
                equal(failing.active, current);
                equal(current.title, "second");
                equal(current.pinned, false);
                equal(failing.recent, before);
            }
        }

        reloaded.delete(renamed);
        equal(reloaded.active.id, second.id);
        equal(reloaded.recent.size(), 1);
        JSONArray remaining = new JSONObject(disk.get()).getJSONArray("history");
        equal(remaining.length(), 1);
        equal(remaining.getJSONObject(0).getString("id"), second.id);
        reloaded.delete(reloaded.active);
        ChatStore empty = new ChatStore(disk.get(), disk::set);
        equal(empty.active, null);
        equal(empty.recent.size(), 0);
        System.out.println("PASS: conversation rename, delete, reload, and rollback after failed saves");
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

        var nativeProvider = new Providers.Provider("claude", "Anthropic", "https://api.anthropic.com/v1", List.of(), List.of(model), -1);
        var compatible = new Providers.Provider("proxy", "Gateway", "https://example.com/v1", List.of(), List.of(model), -1);
        equal(nativeProvider.anthropicMessages(), true);
        equal(compatible.anthropicMessages(), false);
        JSONObject anthropic = ModelApi.payload(model, List.of(new ChatHistory.Turn("recent", "answer", "model")),
                "question", 3, profile.systemPrompt(), "older context", true);
        equal(anthropic.getString("system"), profile.systemPrompt());
        equal(anthropic.getInt("max_tokens"), 4096);
        equal(anthropic.getBoolean("stream"), true);
        equal(anthropic.has("reasoning_effort"), false);
        JSONArray turns = anthropic.getJSONArray("messages");
        equal(turns.length(), 5);
        equal(turns.getJSONObject(0).getString("role"), "user");
        equal(turns.getJSONObject(4).getString("content"), "question");
        equal(ModelApi.payload(model, List.of(), "private", 0, "", "", true).has("system"), false);
        System.out.println("PASS: official Anthropic endpoint and native Messages payload");
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
