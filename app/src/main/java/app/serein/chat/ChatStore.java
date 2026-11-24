package app.serein.chat;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

final class ChatStore {
    static final int MAX_TITLE_LENGTH = 80;

    static final class Chat {
        final String id;
        String title;
        final ChatHistory history;
        long updatedAt;
        boolean pinned;
        boolean archived;
        ChatContext.Checkpoint checkpoint;

        Chat(String id, String title, long updatedAt, ChatHistory history) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.history = history;
        }
    }

    private record SavedChat(String id, String title, long updatedAt, boolean pinned, boolean archived,
                             ChatContext.Checkpoint checkpoint, ChatHistory.Snapshot history) {}

    private final Consumer<String> persist;
    final List<Chat> recent = new ArrayList<>();
    Chat active;

    ChatStore(SharedPreferences prefs, Vault vault) {
        this(load(prefs, vault), json -> vault.write("chat", json, "history", "active_chat"));
        if (prefs.contains("history") || prefs.contains("active_chat")) write(snapshot(), activeId());
    }

    private static String load(SharedPreferences prefs, Vault vault) {
        String saved = vault.read("chat", null);
        if (saved != null) return saved;
        try {
            return new JSONObject()
                    .put("history", new JSONArray(prefs.getString("history", "[]")))
                    .put("active", prefs.getString("active_chat", "")).toString();
        } catch (JSONException error) {
            throw new IllegalStateException("无法读取本机历史", error);
        }
    }

    ChatStore(String savedHistory, Consumer<String> persist) {
        this.persist = persist;
        try {
            JSONObject data = new JSONObject(savedHistory);
            JSONArray saved = data.getJSONArray("history");
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.getJSONObject(i);
                ChatHistory history = ChatHistory.read(entry.getJSONArray("turns"), entry.optInt("leaf", -1));
                Chat chat = new Chat(entry.getString("id"), entry.getString("title"), entry.getLong("updated"), history);
                chat.pinned = entry.optBoolean("pinned", false);
                chat.archived = entry.optBoolean("archived", false);
                JSONObject checkpoint = entry.optJSONObject("checkpoint");
                if (checkpoint != null) chat.checkpoint = new ChatContext.Checkpoint(checkpoint.getString("scope"),
                        checkpoint.getInt("through"), checkpoint.getString("text"));
                if (history.leaf() >= 0) recent.add(chat);
            }
            String last = data.optString("active");
            for (Chat chat : recent) if (chat.id.equals(last)) active = chat;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IllegalStateException("无法读取本机历史", error);
        }
    }

    LocalWrite.Change open(Chat chat) {
        if (chat != null && !recent.contains(chat)) throw new IllegalStateException("会话不存在");
        Chat previous = active;
        if (previous == chat) return LocalWrite.Change.NONE;
        return change(() -> active = chat, () -> active = previous);
    }

    LocalWrite.Change archive(Chat chat, boolean archived) {
        if (!recent.contains(chat)) throw new IllegalStateException("会话不存在");
        if (chat.archived == archived) return LocalWrite.Change.NONE;
        Chat previous = active;
        return change(() -> {
            chat.archived = archived;
            if (archived && active == chat) active = null;
        }, () -> {
            chat.archived = !archived;
            active = previous;
        });
    }

    LocalWrite.Change fork(Chat source, int through) {
        if (source != active || source == null || through < 0) throw new IllegalStateException("会话已切换");
        List<Integer> path = source.history.path(source.history.leaf());
        int end = path.indexOf(through);
        if (end < 0) throw new IllegalStateException("消息不在当前版本内");
        ChatHistory history = new ChatHistory();
        for (int i = 0; i <= end; i++) {
            // Turns and card states are immutable; each history owns its own nodes.
            history.append(history.leaf(), source.history.turn(path.get(i)));
        }
        String suffix = " · 分支";
        int max = MAX_TITLE_LENGTH - suffix.length();
        String title = source.title;
        if (title.codePointCount(0, title.length()) > max) title = title.substring(0, title.offsetByCodePoints(0, max));
        Chat branch = new Chat(UUID.randomUUID().toString(), title + suffix, System.currentTimeMillis(), history);
        return change(() -> {
            active = branch;
            insertRecent(branch);
        }, () -> {
            recent.remove(branch);
            active = source;
        });
    }

    LocalWrite.Change rename(Chat chat, String name) {
        if (!recent.contains(chat)) throw new IllegalStateException("会话不存在");
        String title = name.replaceAll("\\R", " ").strip();
        if (title.isBlank() || title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("请输入 1–" + MAX_TITLE_LENGTH + " 个字符的标题。");
        }
        String previous = chat.title;
        if (title.equals(previous)) return LocalWrite.Change.NONE;
        return change(() -> chat.title = title, () -> chat.title = previous);
    }

    LocalWrite.Change pin(Chat chat, boolean pinned) {
        if (!recent.contains(chat)) throw new IllegalStateException("会话不存在");
        if (chat.pinned == pinned) return LocalWrite.Change.NONE;
        int position = recent.indexOf(chat);
        return change(() -> {
            recent.remove(chat);
            chat.pinned = pinned;
            insertRecent(chat);
        }, () -> {
            recent.remove(chat);
            chat.pinned = !pinned;
            recent.add(position, chat);
        });
    }

    LocalWrite.Change delete(Chat chat) {
        int position = recent.indexOf(chat);
        if (position < 0) throw new IllegalStateException("会话不存在");
        Chat previous = active;
        return change(() -> {
            recent.remove(chat);
            if (active == chat) active = null;
        }, () -> {
            recent.add(position, chat);
            active = previous;
        });
    }

    LocalWrite.Change selectVersion(int id) {
        ChatHistory history = active.history;
        int previous = history.leaf();
        int selected = history.latestDescendant(id);
        if (previous == selected) return LocalWrite.Change.NONE;
        return change(() -> history.select(selected), () -> history.select(previous));
    }

    LocalWrite.Change append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint) {
        return append(parent, prompt, reply, model, checkpoint, null);
    }

    LocalWrite.Change append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint, ChatCard card) {
        return append(parent, prompt, reply, model, checkpoint, card, ChatHistory.ReplyState.COMPLETE);
    }

    LocalWrite.Change append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint, ChatCard card,
                ChatHistory.ReplyState replyState) {
        return append(parent, prompt, reply, model, checkpoint, card, replyState, null);
    }

    LocalWrite.Change append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint, ChatCard card,
                ChatHistory.ReplyState replyState, String searchQuery) {
        return append(parent, prompt, reply, model, checkpoint, card, replyState, searchQuery, null);
    }

    LocalWrite.Change append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint, ChatCard card,
                ChatHistory.ReplyState replyState, String searchQuery, GeneratedImage image) {
        return append(parent, prompt, reply, model, checkpoint, card, replyState, searchQuery, image, List.of(), List.of(), "");
    }

    LocalWrite.Change append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint, ChatCard card,
                ChatHistory.ReplyState replyState, String searchQuery, GeneratedImage image,
                List<ChatFile> attachments, List<ChatFile> references, String imageScope) {
        Chat previous = active;
        Chat target = previous;
        if (target == null) {
            String firstLine = prompt.split("\\R", 2)[0];
            if (firstLine.isBlank() && !attachments.isEmpty()) firstLine = attachments.get(0).name();
            int length = firstLine.codePointCount(0, firstLine.length());
            String title = length <= 24 ? firstLine : firstLine.substring(0, firstLine.offsetByCodePoints(0, 24)) + "…";
            target = new Chat(UUID.randomUUID().toString(), title, 0, new ChatHistory());
        }
        Chat chat = target;
        long oldTime = chat.updatedAt;
        long updated = System.currentTimeMillis();
        int oldPosition = recent.indexOf(chat);
        int oldLeaf = chat.history.leaf();
        ChatContext.Checkpoint oldCheckpoint = chat.checkpoint;
        var turn = new ChatHistory.Turn(prompt, reply, model, card, Map.of(), replyState,
                searchQuery, image, attachments, references, imageScope);
        return change(() -> {
            chat.history.append(parent, turn);
            if (checkpoint != null) chat.checkpoint = checkpoint;
            chat.updatedAt = updated;
            active = chat;
            recent.remove(chat);
            insertRecent(chat);
        }, () -> {
            recent.remove(chat);
            chat.history.rollbackAppend(oldLeaf);
            chat.checkpoint = oldCheckpoint;
            chat.updatedAt = oldTime;
            if (oldPosition >= 0) recent.add(oldPosition, chat);
            active = previous;
        });
    }

    LocalWrite.Change updateCard(Chat chat, int id, String key, Object value) {
        if (active != chat || !chat.history.path(chat.history.leaf()).contains(id)) {
            throw new IllegalStateException("Card is not in the active version");
        }
        ChatHistory.Turn previous = chat.history.turn(id);
        if (previous.card() == null || !previous.card().validState(key, value)) {
            throw new IllegalStateException("Invalid card state");
        }
        if (value.equals(previous.cardState().get(key))) return LocalWrite.Change.NONE;
        Map<String, Object> state = new HashMap<>(previous.cardState());
        state.put(key, value);
        ChatHistory.Turn updated = new ChatHistory.Turn(previous.prompt(), previous.reply(), previous.model(),
                previous.card(), Map.copyOf(state), previous.replyState(), previous.searchQuery(), previous.image(),
                previous.attachments(), previous.references(), previous.imageScope());
        return change(() -> chat.history.replace(id, updated), () -> chat.history.replace(id, previous));
    }

    private void insertRecent(Chat chat) {
        if (chat.pinned) {
            recent.add(0, chat);
            return;
        }
        int position = 0;
        while (position < recent.size() && recent.get(position).pinned) position++;
        while (position < recent.size() && recent.get(position).updatedAt > chat.updatedAt) position++;
        recent.add(position, chat);
    }

    private LocalWrite.Change change(Runnable publish, Runnable undo) {
        // Capture only immutable turn references on the UI thread. JSON and encryption run on disk.
        publish.run();
        try {
            List<SavedChat> saved = snapshot();
            String selected = activeId();
            return new LocalWrite.Change(() -> write(saved, selected), publish);
        } finally { undo.run(); }
    }

    private List<SavedChat> snapshot() {
        var saved = new ArrayList<SavedChat>(recent.size());
        for (Chat chat : recent) saved.add(new SavedChat(chat.id, chat.title, chat.updatedAt, chat.pinned,
                chat.archived, chat.checkpoint, chat.history.snapshot()));
        return saved;
    }

    private String activeId() { return active == null ? "" : active.id; }

    private void write(List<SavedChat> snapshot, String selected) {
        JSONArray saved = new JSONArray();
        try {
            for (SavedChat chat : snapshot) {
                JSONObject row = new JSONObject().put("id", chat.id()).put("title", chat.title())
                        .put("updated", chat.updatedAt()).put("pinned", chat.pinned())
                        .put("turns", chat.history().write()).put("leaf", chat.history().leaf());
                if (chat.archived()) row.put("archived", true);
                var checkpoint = chat.checkpoint();
                if (checkpoint != null) row.put("checkpoint", new JSONObject().put("scope", checkpoint.scope())
                        .put("through", checkpoint.through()).put("text", checkpoint.text()));
                saved.put(row);
            }
            String json = new JSONObject().put("history", saved).put("active", selected).toString();
            persist.accept(json);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String demoReply(String prompt) {
        if (prompt.contains("安排") || prompt.contains("计划")) {
            return "先写下今天非做不可的一件事，给它留一段完整的时间。其余事项按截止时间排，最后给自己留一点空当。\n\n今天最想先完成哪件事？";
        }
        if (prompt.contains("你好") || prompt.equalsIgnoreCase("hello")) {
            return "你好，我是 Serein。今天想聊点什么？";
        }
        return "收到。这是一条离线演示回复。";
    }
}
