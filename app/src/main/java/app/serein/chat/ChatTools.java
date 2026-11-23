package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** Optional capabilities of one user-initiated chat turn. */
final class ChatTools {
    static final String SEARCH = "search_web";
    static final String IMAGE = "generate_image";
    static final int IMAGE_PROMPT_LIMIT = 4000;
    private static final Pattern FLAT_INPUT = Pattern.compile(
            "\\s*\\{\\s*\"(?:query|prompt)\"\\s*:\\s*\"(?:[^\"\\\\\\p{Cntrl}]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*+\"\\s*}\\s*");

    record Options(boolean search, boolean image) {
        boolean enabled() { return search || image; }
        boolean allows(String name) { return (search && SEARCH.equals(name)) || (image && IMAGE.equals(name)); }

        JSONArray schemas(boolean anthropic, boolean cards) throws JSONException {
            JSONArray tools = cards ? ChatCard.tools(anthropic) : new JSONArray();
            if (search) tools.put(schema(anthropic, SEARCH, "query", WebSearch.QUERY_LIMIT,
                    "Search for public web information when needed for the user's current question. "
                            + "Use a short search query. Never send private information or credentials."));
            if (image) tools.put(schema(anthropic, IMAGE, "prompt", IMAGE_PROMPT_LIMIT,
                    "Generate one image inline only when the user asks to create an image. Do not use for an explanation. "
                            + "Send only the visual description; omit private data and credentials."));
            return tools;
        }
    }

    record Invocation(String id, String name, String input, String text, String reasoning) {
        String argument() throws IOException {
            String key = SEARCH.equals(name) ? "query" : "prompt";
            int limit = SEARCH.equals(name) ? 2 * WebSearch.QUERY_LIMIT : IMAGE_PROMPT_LIMIT;
            // These tools accept a flat, single-string object, not arbitrary JSON structures.
            if (input.length() > 6 * limit + 64 || !FLAT_INPUT.matcher(input).matches()) throw badArguments();
            try {
                JSONObject object = new JSONObject(input);
                if (object.length() != 1 || !(object.opt(key) instanceof String value)) throw badArguments();
                String clean = value.strip();
                if (clean.isEmpty() || clean.length() > limit) throw badArguments();
                return SEARCH.equals(name) ? WebSearch.checkedQuery(clean) : clean;
            } catch (JSONException | IllegalArgumentException invalid) { throw badArguments(); }
        }

        void appendResult(JSONArray messages, boolean anthropic, String result) throws JSONException {
            if (anthropic) {
                JSONArray content = new JSONArray();
                if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
                content.put(new JSONObject().put("type", "tool_use").put("id", id).put("name", name)
                        .put("input", new JSONObject(input)));
                messages.put(new JSONObject().put("role", "assistant").put("content", content));
                messages.put(new JSONObject().put("role", "user").put("content", new JSONArray().put(
                        new JSONObject().put("type", "tool_result").put("tool_use_id", id)
                                .put("content", result))));
            } else {
                JSONObject assistant = new JSONObject().put("role", "assistant").put("content", text)
                        .put("tool_calls", new JSONArray().put(new JSONObject().put("type", "function").put("id", id)
                                .put("function", new JSONObject().put("name", name).put("arguments", input))));
                if (!reasoning.isEmpty()) assistant.put("reasoning_content", reasoning);
                messages.put(assistant);
                messages.put(new JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result));
            }
        }
    }

    @FunctionalInterface interface Action { String run(String name, String argument) throws Exception; }

    static String searchResult(List<WebSearch.Hit> hits) throws JSONException {
        return WebSearch.groundedPrompt("以下是网页搜索结果。", hits);
    }

    private static JSONObject schema(boolean anthropic, String name, String key, int limit, String description)
            throws JSONException {
        JSONObject parameters = new JSONObject().put("type", "object").put("additionalProperties", false)
                .put("required", new JSONArray().put(key)).put("properties", new JSONObject().put(key,
                        new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", limit)));
        JSONObject function = new JSONObject().put("name", name).put("description", description)
                .put(anthropic ? "input_schema" : "parameters", parameters);
        return anthropic ? function : new JSONObject().put("type", "function").put("function", function);
    }

    private static IOException badArguments() { return new IOException("模型返回了无效的搜索或生图参数。"); }
}
