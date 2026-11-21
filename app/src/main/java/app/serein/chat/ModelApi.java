package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

final class ModelApi {
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;

    static List<String> models(Providers.Provider provider) throws IOException, JSONException {
        JSONArray items = request(provider, "/models", null).getJSONArray("data");
        TreeSet<String> ids = new TreeSet<>();
        for (int i = 0; i < items.length(); i++) {
            String id = items.getJSONObject(i).optString("id").strip();
            if (!id.isEmpty() && id.length() <= 256) ids.add(id);
        }
        return List.copyOf(ids);
    }

    static String chat(Providers.Choice choice, List<ChatStore.Turn> history, String prompt, int effort)
            throws IOException, JSONException {
        JSONObject response = request(choice.provider(), "/chat/completions", payload(choice.model(), history, prompt, effort));
        String reply = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        if (reply.isBlank() || reply.equals("null")) throw new IOException("模型没有返回可显示的文本。");
        return reply;
    }

    static JSONObject payload(Providers.Model model, List<ChatStore.Turn> history, String prompt, int effort) throws JSONException {
        JSONArray messages = new JSONArray();
        for (ChatStore.Turn turn : history) {
            messages.put(message("user", turn.prompt()));
            messages.put(message("assistant", turn.reply()));
        }
        messages.put(message("user", prompt));
        JSONObject body = new JSONObject().put("model", model.id()).put("messages", messages).put("stream", false);
        if (effort > 0 && !model.reasoning().equals("none")) {
            String level = switch (effort) { case 1 -> "low"; case 2 -> "medium"; default -> "high"; };
            if (model.reasoning().equals("effort")) body.put("reasoning_effort", level);
            if (model.reasoning().equals("deepseek")) {
                body.put("thinking", new JSONObject().put("type", "enabled"));
                body.put("reasoning_effort", effort == 1 ? "high" : "max");
            }
        }
        return body;
    }

    private static JSONObject message(String role, String content) throws JSONException {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static JSONObject request(Providers.Provider provider, String path, JSONObject body) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(Providers.httpsBase(provider.base) + path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("Accept", "application/json");
        if (!provider.secret().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + provider.secret());
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (var stream = connection.getOutputStream()) { stream.write(data); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException(switch (status) {
                case 401, 403 -> "服务商拒绝访问（HTTP " + status + "），请检查当前 Key。";
                case 429 -> "服务商限流或额度不足（HTTP 429）。";
                default -> "服务商返回 HTTP " + status + "。";
            });
            try (InputStream stream = connection.getInputStream()) {
                byte[] data = stream.readNBytes(MAX_RESPONSE + 1);
                if (data.length > MAX_RESPONSE) throw new IOException("服务商响应过大。");
                return new JSONObject(new String(data, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
