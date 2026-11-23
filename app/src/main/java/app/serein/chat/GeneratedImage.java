package app.serein.chat;

import org.json.JSONException;
import org.json.JSONObject;

record GeneratedImage(String id, String provider, String model, String size, String mime) {
    GeneratedImage {
        if (id == null || !id.matches("[0-9a-f]{32}") || provider.isBlank() || provider.length() > 128
                || model.isBlank() || model.length() > 256 || !ImageGeneration.validSize(size)
                || !ImageLibrary.MIME_TYPES.contains(mime)) {
            throw new IllegalArgumentException("Invalid generated image record");
        }
    }

    JSONObject write() throws JSONException {
        return new JSONObject().put("id", id).put("provider", provider).put("model", model).put("size", size).put("mime", mime);
    }

    static GeneratedImage read(JSONObject value) throws JSONException {
        return new GeneratedImage(value.getString("id"), value.getString("provider"), value.getString("model"),
                value.optString("size"), value.getString("mime"));
    }

    String extension() {
        return mime.equals("image/jpeg") ? "jpg" : mime.substring("image/".length());
    }
}
