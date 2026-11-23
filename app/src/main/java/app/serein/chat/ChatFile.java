package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record ChatFile(String id, String name, String mime, int bytes, int characters) {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_IMAGE = 2 * 1024 * 1024;
    static final int MAX_TEXT = 60_000;
    static final int MAX_ATTACHMENTS = 3;
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp");

    ChatFile {
        if (!validId(id) || name.isBlank() || !name.equals(cleanName(name)) || bytes <= 0 || bytes > MAX_BYTES
                || (IMAGES.contains(mime) ? characters != 0 || bytes > MAX_IMAGE
                : !"text/plain".equals(mime) || characters <= 0 || characters > MAX_TEXT)) {
            throw new IllegalArgumentException("Invalid chat file record");
        }
    }

    boolean image() { return IMAGES.contains(mime); }

    static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{32}"); }

    static String cleanName(String name) {
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
        return ReferenceSearch.clip(clean, 160).stripTrailing();
    }

    JSONObject write() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("mime", mime)
                .put("bytes", bytes).put("characters", characters);
    }

    static ChatFile read(JSONObject data) throws JSONException {
        return new ChatFile(data.getString("id"), data.getString("name"), data.getString("mime"),
                data.getInt("bytes"), data.getInt("characters"));
    }

    static List<ChatFile> readList(JSONArray data, int limit) throws JSONException {
        if (data == null) return List.of();
        if (data.length() > limit) throw new JSONException("Too many chat files");
        List<ChatFile> files = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) files.add(read(data.getJSONObject(i)));
        return List.copyOf(files);
    }

    static JSONArray writeList(List<ChatFile> files) throws JSONException {
        JSONArray array = new JSONArray();
        for (ChatFile file : files) array.put(file.write());
        return array;
    }
}
