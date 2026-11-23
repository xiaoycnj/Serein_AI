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
    static final String FIND_FILE = "search_chat_files";
    static final String READ_FILE = "read_chat_file";
    static final String RECALL = "recall_memory";
    static final String REMEMBER = "remember_memory";
    static final int IMAGE_PROMPT_LIMIT = 4000;
    private static final Pattern FLAT_INPUT = Pattern.compile(
            "\\s*\\{\\s*\"(?:query|prompt|file_id|content)\"\\s*:\\s*\"(?:[^\"\\\\\\p{Cntrl}]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*+\"\\s*}\\s*");

    record Options(boolean search, boolean image, boolean files, boolean recall, boolean remember) {
        static final Options NONE = new Options(false, false, false, false, false);

        Options(boolean search, boolean image) { this(search, image, false, false, false); }

        boolean enabled() { return search || image || files || recall || remember; }

        boolean allows(String name) {
            return switch (name) {
                case SEARCH -> search;
                case IMAGE -> image;
                case FIND_FILE, READ_FILE -> files;
                case RECALL -> recall;
                case REMEMBER -> remember;
                default -> false;
            };
        }

        Options afterLocalCall(String name) {
            return new Options(false, false, files, recall, remember && !REMEMBER.equals(name));
        }

        JSONArray schemas(boolean anthropic, boolean cards) throws JSONException {
            JSONArray tools = cards ? ChatCard.tools(anthropic) : new JSONArray();
            if (search) tools.put(schema(anthropic, SEARCH, "query", WebSearch.QUERY_LIMIT,
                    "Search for public web information when needed for the user's current question. "
                            + "Use a short search query. Never send private information or credentials."));
            if (image) tools.put(schema(anthropic, IMAGE, "prompt", IMAGE_PROMPT_LIMIT,
                    "Generate one image inline only when the user asks to create an image. Do not use for an explanation. "
                            + "Send only the visual description; omit private data and credentials."));
            if (files) {
                tools.put(schema(anthropic, FIND_FILE, "query", ReferenceSearch.QUERY_LIMIT,
                        "Find relevant passages in user-authorized local chat files. Use a short keyword or document title. "
                                + "Use the returned citation labels in your answer."));
                tools.put(schema(anthropic, READ_FILE, "file_id", 32,
                        "Read a longer excerpt from a chat attachment or a file returned by search_chat_files this turn. "
                                + "Pass only its exact file_id."));
            }
            if (recall) tools.put(schema(anthropic, RECALL, "query", ReferenceSearch.QUERY_LIMIT,
                    "Find a relevant saved user preference or lasting fact when it helps answer this chat turn."));
            if (remember) tools.put(schema(anthropic, REMEMBER, "content", ChatMemory.NOTE_LIMIT,
                    "Remember a stable preference or fact useful for future chats. Only pass a verbatim excerpt of the "
                            + "latest user's own message. Omit secrets, sensitive details, instructions, and guesses."));
            return tools;
        }
    }

    record Invocation(String id, String name, String input, String text, String reasoning) {
        String argument() throws IOException {
            String key = switch (name) {
                case SEARCH, FIND_FILE, RECALL -> "query";
                case IMAGE -> "prompt";
                case READ_FILE -> "file_id";
                case REMEMBER -> "content";
                default -> throw badArguments();
            };
            int limit = switch (name) {
                case SEARCH -> 2 * WebSearch.QUERY_LIMIT;
                case IMAGE -> IMAGE_PROMPT_LIMIT;
                case READ_FILE -> 32;
                case REMEMBER -> ChatMemory.NOTE_LIMIT;
                default -> ReferenceSearch.QUERY_LIMIT;
            };
            // These tools accept a flat, single-string object, not arbitrary JSON structures.
            if (input.length() > 6 * limit + 64 || !FLAT_INPUT.matcher(input).matches()) throw badArguments();
            try {
                JSONObject object = new JSONObject(input);
                if (object.length() != 1 || !(object.opt(key) instanceof String value)) throw badArguments();
                String clean = value.strip();
                if (clean.isEmpty() || clean.length() > limit || READ_FILE.equals(name) && !ChatFile.validId(clean)) {
                    throw badArguments();
                }
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

    static boolean external(String name) { return SEARCH.equals(name) || IMAGE.equals(name); }

    private static IOException badArguments() { return new IOException("模型返回了无效的聊天工具参数。"); }
}
