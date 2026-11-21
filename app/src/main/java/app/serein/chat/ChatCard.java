package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Android subset of AstraAgent's render_iui document format (MIT).
record ChatCard(String title, String description, List<Block> blocks) {
    static final String TOOL = "render_iui";
    static final int MAX_ARGUMENTS = 48_000;
    static final String GUIDANCE = "Prefer a normal text response. Use render_iui only if metrics, comparison cards, "
            + "a checklist or a single-select question would improve the answer. A call ends the current reply. "
            + "Use purpose=question for choices. Do not request credentials or permission to execute actions.";

    record Block(String id, String type, String title, String text, List<Item> items) {}
    record Item(String id, String label, String description, String value, boolean checked) {}

    static JSONArray tools(boolean anthropic) throws JSONException {
        JSONObject schema = new JSONObject("""
                {"type":"object","required":["title","blocks"],"additionalProperties":false,"properties":{
                  "title":{"type":"string","maxLength":120},"description":{"type":"string","maxLength":600},
                  "purpose":{"type":"string","enum":["display","question"]},
                  "blocks":{"type":"array","minItems":1,"maxItems":6,"items":{"type":"object",
                    "required":["id","type"],"additionalProperties":false,"properties":{
                    "id":{"type":"string","maxLength":64},
                    "type":{"type":"string","enum":["text","metrics","cards","checklist","choices"]},
                    "title":{"type":"string","maxLength":120},"text":{"type":"string","maxLength":1200},
                    "items":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object",
                      "required":["label"],"additionalProperties":false,"properties":{
                      "id":{"type":"string","maxLength":64},"label":{"type":"string","maxLength":100},
                      "description":{"type":"string","maxLength":300},"value":{"type":["string","number"]},
                      "checked":{"type":"boolean"}
                    }}}
                  }}}
                }}
                """);
        JSONObject function = new JSONObject().put("name", TOOL).put("description", GUIDANCE)
                .put(anthropic ? "input_schema" : "parameters", schema);
        return new JSONArray().put(anthropic ? function
                : new JSONObject().put("type", "function").put("function", function));
    }

    static ChatCard parse(String source) throws JSONException {
        if (source.length() > MAX_ARGUMENTS || source.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENTS) {
            throw new JSONException("Card is too large");
        }
        // Bound nesting before using Android's recursive JSON parser.
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') {
                if (++depth > 6) throw new JSONException("Card nesting limit exceeded");
            } else if ((ch == '}' || ch == ']') && --depth < 0) {
                throw new JSONException("Invalid card");
            } else if (ch == '\'' || ch == '/' || ch == '#') {
                throw new JSONException("Invalid card JSON");
            }
        }
        JSONTokener reader = new JSONTokener(source);
        if (!(reader.nextValue() instanceof JSONObject object) || reader.nextClean() != 0) {
            throw new JSONException("Expected one card object");
        }
        return read(object);
    }

    static ChatCard read(JSONObject object) throws JSONException {
        fields(object, Set.of("title", "description", "purpose", "layout", "blocks"));
        String purpose = string(object, "purpose", 12, false);
        String layout = string(object, "layout", 12, false);
        if ((!purpose.isEmpty() && !Set.of("display", "question").contains(purpose))
                || (!layout.isEmpty() && !Set.of("stack", "columns").contains(layout))) {
            throw new JSONException("Unsupported card purpose or layout");
        }
        JSONArray encoded = object.getJSONArray("blocks");
        if (encoded.length() < 1 || encoded.length() > 6) throw new JSONException("Invalid block count");
        List<Block> blocks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < encoded.length(); i++) {
            JSONObject block = encoded.getJSONObject(i);
            fields(block, Set.of("id", "type", "title", "text", "items", "prompt"));
            String id = id(string(block, "id", 64, true), ids);
            String type = string(block, "type", 12, true);
            String heading = string(block, "title", 120, false);
            String body = string(block, "text", 1200, false);
            String prompt = string(block, "prompt", 400, false);
            if (!prompt.isEmpty()) body = body.isEmpty() ? prompt : body + "\n" + prompt;
            if (body.length() > 1200) throw new JSONException("Block text too long");
            List<Item> items = new ArrayList<>();
            if (type.equals("text")) {
                if (block.has("items") || body.isBlank()) throw new JSONException("Invalid text block");
            } else {
                if (!Set.of("metrics", "cards", "checklist", "choices").contains(type)) {
                    throw new JSONException("Unsupported block type");
                }
                JSONArray entries = block.getJSONArray("items");
                if (entries.length() < 1 || entries.length() > 8) throw new JSONException("Invalid item count");
                Set<String> itemIds = new HashSet<>();
                for (int j = 0; j < entries.length(); j++) {
                    JSONObject entry = entries.getJSONObject(j);
                    fields(entry, Set.of("id", "label", "description", "value", "checked"));
                    String key = id(entry.has("id") ? string(entry, "id", 64, true) : "item" + j, itemIds);
                    Object value = entry.opt("value");
                    if (value instanceof Number number) {
                        if (!Double.isFinite(number.doubleValue()) || Math.abs(number.doubleValue()) > 1e15) {
                            throw new JSONException("Invalid item value");
                        }
                        value = value.toString();
                    } else value = string(entry, "value", 200, false);
                    Object checked = entry.has("checked") ? entry.get("checked") : false;
                    if (!(checked instanceof Boolean flag)) throw new JSONException("Invalid checkbox value");
                    items.add(new Item(key, string(entry, "label", 100, true),
                            string(entry, "description", 300, false), (String) value, flag));
                }
            }
            blocks.add(new Block(id, type, heading, body, List.copyOf(items)));
        }
        return new ChatCard(string(object, "title", 120, true), string(object, "description", 600, false), List.copyOf(blocks));
    }

    JSONObject write() throws JSONException {
        JSONArray encoded = new JSONArray();
        for (Block block : blocks) {
            JSONObject row = new JSONObject().put("id", block.id()).put("type", block.type())
                    .put("title", block.title()).put("text", block.text());
            if (!block.items().isEmpty()) {
                JSONArray entries = new JSONArray();
                for (Item item : block.items()) entries.put(new JSONObject().put("id", item.id())
                        .put("label", item.label()).put("description", item.description())
                        .put("value", item.value()).put("checked", item.checked()));
                row.put("items", entries);
            }
            encoded.put(row);
        }
        return new JSONObject().put("title", title).put("description", description).put("blocks", encoded);
    }

    boolean validState(String key, Object value) {
        for (Block block : blocks) {
            for (Item item : block.items()) {
                if (block.type().equals("choices") && block.id().equals(key) && item.id().equals(value)) return true;
                if (block.type().equals("checklist") && (block.id() + "/" + item.id()).equals(key)
                        && value instanceof Boolean) return true;
            }
        }
        return false;
    }

    String transcript() {
        StringBuilder result = new StringBuilder(title);
        line(result, description);
        for (Block block : blocks) {
            line(result, block.title());
            line(result, block.text());
            for (Item item : block.items()) {
                line(result, "- " + item.label() + (item.value().isEmpty() ? "" : ": " + item.value()));
                line(result, item.description());
            }
        }
        return result.toString();
    }

    String draft(Map<String, Object> state) {
        StringBuilder result = new StringBuilder();
        for (Block block : blocks) {
            for (Item item : block.items()) {
                if ((block.type().equals("choices") && item.id().equals(state.get(block.id())))
                        || (block.type().equals("checklist") && Boolean.TRUE.equals(
                                state.getOrDefault(block.id() + "/" + item.id(), item.checked())))) {
                    line(result, "- " + item.label());
                }
            }
        }
        return result.length() == 0 ? "" : title + "\n" + result;
    }

    private static void line(StringBuilder result, String value) {
        if (value.isBlank()) return;
        if (result.length() != 0) result.append('\n');
        result.append(value);
    }

    private static void fields(JSONObject value, Set<String> allowed) throws JSONException {
        var keys = value.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new JSONException("Unexpected card field");
    }

    private static String id(String value, Set<String> seen) throws JSONException {
        if (!value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !seen.add(value)) {
            throw new JSONException("Invalid or duplicate card ID");
        }
        return value;
    }

    private static String string(JSONObject object, String key, int limit, boolean required) throws JSONException {
        if (!required && !object.has(key)) return "";
        if (object.opt(key) instanceof String value && value.length() <= limit && (!required || !value.isBlank())) {
            return value;
        }
        throw new JSONException("Invalid card string: " + key);
    }
}
