package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

final class IuiCall {
    private static final int MAX_TOOL_NAME = 64;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
    private final StringBuilder id = new StringBuilder();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final boolean chatTools;
    private JSONObject initial;
    private boolean seen;
    private boolean finished;
    private int index = -1;

    IuiCall() { this(false); }

    IuiCall(boolean chatTools) { this.chatTools = chatTools; }

    static void capture(IuiCall call, JSONObject value, String field) throws IOException, JSONException {
        if (call == null || !call.chatTools || value == null) return;
        if (value.opt(field) instanceof String) append(call.text, value, field, 1_000_000);
        if (value.opt("reasoning_content") instanceof String) append(call.reasoning, value, "reasoning_content", 1_000_000);
    }

    static void completions(IuiCall call, JSONObject message) throws IOException, JSONException {
        if (message == null || message.isNull("tool_calls")) return;
        JSONArray parts = message.getJSONArray("tool_calls");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (call == null || part.optInt("index", i) != 0
                    || !"function".equals(part.optString("type", "function"))) throw unsupported();
            call.seen = true;
            if (call.chatTools) append(call.id, part, "id", 256);
            JSONObject function = part.optJSONObject("function");
            if (function == null) continue;
            append(call.name, function, "name", MAX_TOOL_NAME);
            append(call.arguments, function, "arguments", ChatCard.MAX_ARGUMENTS);
        }
    }

    static void anthropicStart(IuiCall call, int index, JSONObject block) throws IOException, JSONException {
        if (block == null || !"tool_use".equals(block.optString("type"))) return;
        if (call == null || call.seen || index < 0) throw unsupported();
        call.seen = true;
        call.index = index;
        if (call.chatTools) append(call.id, block, "id", 256);
        append(call.name, block, "name", MAX_TOOL_NAME);
        call.initial = block.getJSONObject("input");
    }

    static void anthropicDelta(IuiCall call, int index, JSONObject delta) throws IOException, JSONException {
        if (delta == null || !"input_json_delta".equals(delta.optString("type"))) return;
        if (call == null || !call.seen || call.index != index || call.initial.length() != 0) throw unsupported();
        append(call.arguments, delta, "partial_json", ChatCard.MAX_ARGUMENTS);
    }

    static void finish(IuiCall call) throws IOException {
        if (call == null || !call.seen) throw unsupported();
        call.finished = true;
    }

    ChatCard result() throws IOException {
        if (!seen) return null;
        if (!finished || !ChatCard.TOOL.contentEquals(name)) throw unsupported();
        try {
            return ChatCard.parse(arguments.length() == 0 && initial != null ? initial.toString() : arguments.toString());
        } catch (JSONException invalid) {
            throw new IOException("模型返回的交互卡片格式无效或超出限制。", invalid);
        }
    }

    String name() throws IOException {
        if (!seen) return "";
        if (!finished || name.length() == 0) throw unsupported();
        return name.toString();
    }

    ChatTools.Invocation invocation() throws IOException {
        String tool = name();
        if (!chatTools || !id.toString().matches("[A-Za-z0-9_-]{1,256}")) throw unsupported();
        String input = arguments.length() == 0 && initial != null ? initial.toString() : arguments.toString();
        return new ChatTools.Invocation(id.toString(), tool, input, text.toString(), reasoning.toString());
    }

    private static void append(StringBuilder buffer, JSONObject source, String key, int limit)
            throws JSONException, IOException {
        if (!source.has(key)) return;
        if (!(source.get(key) instanceof String value) || value.length() > limit - buffer.length()) throw unsupported();
        buffer.append(value);
    }

    private static IOException unsupported() {
        return new IOException("模型返回了不受支持或不完整的工具调用。");
    }
}
