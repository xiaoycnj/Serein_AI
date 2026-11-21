package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

final class IuiCall {
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
    private JSONObject initial;
    private boolean seen;
    private boolean finished;
    private int index = -1;

    static void completions(IuiCall call, JSONObject message) throws IOException, JSONException {
        if (message == null || message.isNull("tool_calls")) return;
        JSONArray parts = message.getJSONArray("tool_calls");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (call == null || part.optInt("index", i) != 0
                    || !"function".equals(part.optString("type", "function"))) throw unsupported();
            call.seen = true;
            JSONObject function = part.optJSONObject("function");
            if (function == null) continue;
            append(call.name, function, "name", ChatCard.TOOL.length());
            append(call.arguments, function, "arguments", ChatCard.MAX_ARGUMENTS);
        }
    }

    static void anthropicStart(IuiCall call, int index, JSONObject block) throws IOException, JSONException {
        if (block == null || !"tool_use".equals(block.optString("type"))) return;
        if (call == null || call.seen || index < 0) throw unsupported();
        call.seen = true;
        call.index = index;
        append(call.name, block, "name", ChatCard.TOOL.length());
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

    private static void append(StringBuilder buffer, JSONObject source, String key, int limit)
            throws JSONException, IOException {
        if (!source.has(key)) return;
        if (!(source.get(key) instanceof String value) || value.length() > limit - buffer.length()) throw unsupported();
        buffer.append(value);
    }

    private static IOException unsupported() {
        return new IOException("模型返回了不受支持或不完整的交互卡片调用。");
    }
}
