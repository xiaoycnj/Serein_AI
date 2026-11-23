package app.serein.chat;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

final class ImageGeneration {
    static final int MAX_IMAGE = 10 * 1024 * 1024;
    private static final int MAX_JSON = (MAX_IMAGE + 2) / 3 * 4 + 65_536;
    private static final Set<String> SIZES = Set.of("", "1024x1024", "1024x1536", "1536x1024");

    record Request(Providers.Provider provider, String model, String size) {}

    static boolean validSize(String size) { return SIZES.contains(size); }

    static JSONObject payload(String prompt, String model, String size) throws JSONException {
        if (prompt.isBlank() || model.isBlank() || model.length() > 256 || !validSize(size)) {
            throw new IllegalArgumentException("生图提示词、模型 ID 或尺寸无效");
        }
        JSONObject request = new JSONObject().put("model", model).put("prompt", prompt).put("n", 1);
        if (!size.isEmpty()) request.put("size", size);
        return request;
    }

    static byte[] generate(Request request, String prompt, ModelApi.Cancellation cancellation) throws Exception {
        if (request.provider().anthropicMessages()) throw new IOException("生图需要支持 Images API 的服务商。");
        cancellation.check();
        var connection = ModelApi.open(request.provider(), "/images/generations", "application/json");
        connection.setReadTimeout(180_000);
        JSONObject result;
        try {
            cancellation.attach(connection);
            ModelApi.writeJson(connection, payload(prompt, request.model(), request.size()));
            try (var input = ModelApi.response(connection)) {
                byte[] json = input.readNBytes(MAX_JSON + 1);
                if (json.length > MAX_JSON) throw new IOException("生图响应超过大小限制。");
                result = new JSONObject(new String(json, StandardCharsets.UTF_8)).getJSONArray("data").getJSONObject(0);
            }
        } catch (JSONException error) {
            throw new IOException("生图服务未返回可读取的图片。", error);
        } finally {
            cancellation.detach();
            connection.disconnect();
        }
        cancellation.check();
        String encoded = result.isNull("b64_json") ? "" : result.optString("b64_json");
        if (!encoded.isEmpty()) return decode(encoded);
        String url = result.isNull("url") ? "" : result.optString("url");
        URI target = imageUrl(request.provider().base, url);
        var download = ModelApi.openEndpoint(target.toString(), "image/png, image/jpeg, image/webp");
        download.setReadTimeout(25_000);
        try {
            cancellation.attach(download);
            try (var input = ModelApi.response(download)) {
                byte[] image = input.readNBytes(MAX_IMAGE + 1);
                cancellation.check();
                return bounded(image);
            }
        } finally {
            cancellation.detach();
            download.disconnect();
        }
    }

    static byte[] decode(String value) throws IOException {
        if (value.length() > (MAX_IMAGE + 2) / 3 * 4) throw new IOException("生图结果超过大小限制。");
        try { return bounded(Base64.getDecoder().decode(value)); }
        catch (IllegalArgumentException error) { throw new IOException("生图结果不是有效的 Base64 图片。", error); }
    }

    private static byte[] bounded(byte[] data) throws IOException {
        if (data.length == 0 || data.length > MAX_IMAGE) throw new IOException("生图结果为空或超过大小限制。");
        return data;
    }

    static URI imageUrl(String base, String value) throws IOException {
        try {
            if (value.length() > 8192) throw new IllegalArgumentException();
            URI origin = URI.create(base);
            URI link = URI.create(value);
            String host = link.getHost();
            if (!"https".equalsIgnoreCase(link.getScheme()) || host == null || link.getRawUserInfo() != null
                    || link.getRawFragment() != null) throw new IllegalArgumentException();
            int linkPort = link.getPort() == -1 ? 443 : link.getPort();
            int originPort = origin.getPort() == -1 ? 443 : origin.getPort();
            if ((host.equalsIgnoreCase(origin.getHost()) && linkPort == originPort)
                    || (linkPort == 443 && host.toLowerCase(java.util.Locale.ROOT).endsWith(".blob.core.windows.net"))) return link;
        } catch (IllegalArgumentException ignored) {}
        throw new IOException("生图下载地址不受支持，请使用 Base64 或同源 HTTPS 图片。");
    }
}
