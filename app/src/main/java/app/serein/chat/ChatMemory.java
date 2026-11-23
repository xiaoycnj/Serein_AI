package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** The model chooses what to retain from the user's own wording, as in AstraAgent's remember tool. */
final class ChatMemory {
    static final int NOTE_LIMIT = 400;
    private static final int CAPACITY = 128;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:密[码钥]|口令|验证码|身份证|token|password|passphrase|credential|api[ _-]?key|"
                    + "bearer\\s|private key|sk-[\\w-]{8,}|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|\\d{6,})");

    record Entry(String text, long savedAt) {}
    private record Hit(Entry entry, int score) {}
    enum Outcome { SAVED, ALREADY_SAVED, UNSUITABLE, FULL }

    private final Consumer<String> persist;
    private List<Entry> entries;

    ChatMemory(Vault vault) { this(vault.read("memory", "[]"), data -> vault.write("memory", data)); }

    ChatMemory(String saved, Consumer<String> persist) {
        this.persist = persist;
        try {
            JSONArray data = new JSONArray(saved);
            if (data.length() > CAPACITY) throw new JSONException("Too many memories");
            List<Entry> loaded = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.getJSONObject(i);
                String text = row.getString("text");
                if (text.isBlank() || text.length() > NOTE_LIMIT) throw new JSONException("Invalid memory");
                loaded.add(new Entry(text, row.getLong("savedAt")));
            }
            entries = List.copyOf(loaded);
        } catch (JSONException error) { throw new IllegalStateException("无法读取本机记忆", error); }
    }

    synchronized List<Entry> entries() { return entries; }

    synchronized Outcome assess(String note, String userMessage) {
        String text = note.strip();
        if (text.length() < 4 || text.length() > NOTE_LIMIT || !userMessage.contains(text) || SECRET.matcher(userMessage).find()) {
            return Outcome.UNSUITABLE;
        }
        for (Entry entry : entries) if (entry.text().equals(text)) return Outcome.ALREADY_SAVED;
        return entries.size() == CAPACITY ? Outcome.FULL : Outcome.SAVED;
    }

    synchronized Outcome remember(String note, String userMessage) {
        Outcome outcome = assess(note, userMessage);
        if (outcome != Outcome.SAVED) return outcome;
        List<Entry> next = new ArrayList<>(entries);
        next.add(0, new Entry(note.strip(), System.currentTimeMillis()));
        save(next);
        return Outcome.SAVED;
    }

    synchronized List<Entry> search(String value) {
        var query = new ReferenceSearch.Query(value);
        return entries.stream().map(entry -> new Hit(entry, query.match(entry.text()).score()))
                .filter(hit -> hit.score() > 0).sorted(Comparator.comparingInt(Hit::score).reversed())
                .limit(3).map(Hit::entry).collect(java.util.stream.Collectors.toList());
    }

    synchronized void clear() { save(List.of()); }

    private void save(List<Entry> next) {
        try {
            var data = new JSONArray();
            for (Entry entry : next) data.put(new JSONObject().put("text", entry.text()).put("savedAt", entry.savedAt()));
            persist.accept(data.toString());
            entries = List.copyOf(next);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
