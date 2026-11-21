package app.serein.chat;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ChatStore {
    static final class Chat {
        final String id;
        final String title;
        final ChatHistory history;
        long updatedAt;
        ChatContext.Checkpoint checkpoint;

        Chat(String id, String title, long updatedAt, ChatHistory history) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.history = history;
        }
    }

    private final Vault vault;
    final List<Chat> recent = new ArrayList<>();
    Chat active;

    ChatStore(SharedPreferences prefs, Vault vault) {
        this.vault = vault;
        String encrypted = vault.read("chat", null);
        try {
            JSONObject data = encrypted == null ? new JSONObject()
                    .put("history", new JSONArray(prefs.getString("history", "[]")))
                    .put("active", prefs.getString("active_chat", "")) : new JSONObject(encrypted);
            JSONArray saved = data.getJSONArray("history");
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.getJSONObject(i);
                ChatHistory history = ChatHistory.read(entry.getJSONArray("turns"), entry.optInt("leaf", -1));
                Chat chat = new Chat(entry.getString("id"), entry.getString("title"), entry.getLong("updated"), history);
                JSONObject checkpoint = entry.optJSONObject("checkpoint");
                if (checkpoint != null) chat.checkpoint = new ChatContext.Checkpoint(checkpoint.getString("scope"),
                        checkpoint.getInt("through"), checkpoint.getString("text"));
                if (history.leaf() >= 0) recent.add(chat);
            }
            String last = data.optString("active");
            for (Chat chat : recent) if (chat.id.equals(last)) active = chat;
            if (encrypted == null && (prefs.contains("history") || prefs.contains("active_chat"))) save();
        } catch (JSONException | IllegalArgumentException error) {
            throw new IllegalStateException("无法读取本机历史", error);
        }
    }

    void open(Chat chat) {
        Chat previous = active;
        active = chat;
        try { save(); }
        catch (RuntimeException error) { active = previous; throw error; }
    }

    void selectVersion(int id) {
        int previous = active.history.leaf();
        active.history.select(active.history.latestDescendant(id));
        try { save(); }
        catch (RuntimeException error) { active.history.select(previous); throw error; }
    }

    void append(int parent, String prompt, String reply, String model, ChatContext.Checkpoint checkpoint) {
        Chat previous = active;
        if (active == null) {
            String firstLine = prompt.split("\\R", 2)[0];
            int length = firstLine.codePointCount(0, firstLine.length());
            String title = length <= 24 ? firstLine : firstLine.substring(0, firstLine.offsetByCodePoints(0, 24)) + "…";
            active = new Chat(UUID.randomUUID().toString(), title, 0, new ChatHistory());
        }
        long oldTime = active.updatedAt;
        int oldPosition = recent.indexOf(active);
        int oldLeaf = active.history.leaf();
        ChatContext.Checkpoint oldCheckpoint = active.checkpoint;
        active.history.append(parent, new ChatHistory.Turn(prompt, reply, model));
        if (checkpoint != null) active.checkpoint = checkpoint;
        active.updatedAt = System.currentTimeMillis();
        recent.remove(active);
        recent.add(0, active);
        try { save(); }
        catch (RuntimeException error) {
            recent.remove(active);
            active.history.rollbackAppend(oldLeaf);
            active.checkpoint = oldCheckpoint;
            active.updatedAt = oldTime;
            if (oldPosition >= 0) recent.add(oldPosition, active);
            active = previous;
            throw error;
        }
    }

    private void save() {
        JSONArray saved = new JSONArray();
        try {
            for (Chat chat : recent) {
                JSONObject row = new JSONObject().put("id", chat.id).put("title", chat.title)
                        .put("updated", chat.updatedAt).put("turns", chat.history.write()).put("leaf", chat.history.leaf());
                if (chat.checkpoint != null) row.put("checkpoint", new JSONObject().put("scope", chat.checkpoint.scope())
                        .put("through", chat.checkpoint.through()).put("text", chat.checkpoint.text()));
                saved.put(row);
            }
            String json = new JSONObject().put("history", saved).put("active", active == null ? "" : active.id).toString();
            vault.write("chat", json, "history", "active_chat");
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
