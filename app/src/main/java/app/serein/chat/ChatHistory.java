package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ChatHistory {
    record Turn(String prompt, String reply, String model, ChatCard card, Map<String, Object> cardState) {
        Turn(String prompt, String reply, String model) { this(prompt, reply, model, null, Map.of()); }

        String assistantText() {
            return card == null ? reply : (reply.isBlank() ? "" : reply + "\n\n") + card.transcript();
        }
    }
    private record Node(int parent, Turn turn) {}

    private final List<Node> nodes = new ArrayList<>();
    private final Map<Integer, List<Integer>> children = new HashMap<>();
    private int leaf = -1;

    static ChatHistory read(JSONArray saved, int selected) throws JSONException {
        ChatHistory history = new ChatHistory();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.getJSONObject(i);
            int parent = row.optInt("parent", i - 1);
            if (parent < -1 || parent >= i) throw new JSONException("Invalid chat parent");
            ChatCard card = row.has("card") ? ChatCard.read(row.getJSONObject("card")) : null;
            Map<String, Object> state = new HashMap<>();
            JSONObject savedState = row.optJSONObject("cardState");
            if (card != null && savedState != null) {
                var keys = savedState.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = savedState.get(key);
                    if (card.validState(key, value)) state.put(key, value);
                }
            }
            history.append(parent, new Turn(row.getString("prompt"), row.getString("reply"),
                    row.optString("model", "Serein"), card, Map.copyOf(state)));
        }
        if (selected >= 0) history.select(selected);
        return history;
    }

    JSONArray write() throws JSONException {
        JSONArray saved = new JSONArray();
        for (Node node : nodes) {
            Turn turn = node.turn();
            JSONObject row = new JSONObject().put("parent", node.parent()).put("prompt", turn.prompt())
                    .put("reply", turn.reply()).put("model", turn.model());
            if (turn.card() != null) row.put("card", turn.card().write()).put("cardState", new JSONObject(turn.cardState()));
            saved.put(row);
        }
        return saved;
    }

    int leaf() { return leaf; }
    int parent(int id) { return nodes.get(id).parent(); }
    Turn turn(int id) { return nodes.get(id).turn(); }

    void replace(int id, Turn turn) { nodes.set(id, new Node(parent(id), turn)); }

    List<Integer> path(int tip) {
        List<Integer> path = new ArrayList<>();
        for (int id = tip; id >= 0; id = parent(id)) path.add(id);
        Collections.reverse(path);
        return path;
    }

    List<Integer> variants(int id) {
        return children.get(parent(id));
    }

    int latestDescendant(int id) {
        // Parents precede their children. Mark the subtree in a single pass.
        boolean[] inBranch = new boolean[nodes.size()];
        inBranch[id] = true;
        int latest = id;
        for (int i = id + 1; i < nodes.size(); i++) {
            int parent = parent(i);
            if (parent >= id && inBranch[parent]) {
                inBranch[i] = true;
                latest = i;
            }
        }
        return latest;
    }

    void select(int id) {
        if (id < -1 || id >= nodes.size()) throw new IllegalArgumentException("Invalid chat selection");
        leaf = id;
    }

    void append(int parent, Turn turn) {
        if (parent < -1 || parent >= nodes.size()) throw new IllegalArgumentException("Invalid chat parent");
        nodes.add(new Node(parent, turn));
        leaf = nodes.size() - 1;
        children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(leaf);
    }

    void rollbackAppend(int previous) {
        int parent = nodes.remove(nodes.size() - 1).parent();
        List<Integer> siblings = children.get(parent);
        siblings.remove(siblings.size() - 1);
        if (siblings.isEmpty()) children.remove(parent);
        select(previous);
    }
}
