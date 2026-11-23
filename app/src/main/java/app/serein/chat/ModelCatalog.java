package app.serein.chat;

import android.content.Context;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class ModelCatalog {
    static final int MAX_BYTES = 1_000_000;
    record Entry(String label, String publisher, String icon, int inputLimit, int tier, double price,
                 boolean routeDisabled, boolean supportsTools) {}

    private static final Set<String> OPENAI_EFFORT = Set.of(
            "gpt-5", "gpt-5-2025-08-07", "gpt-5-mini", "gpt-5-mini-2025-08-07",
            "gpt-5-nano", "gpt-5-nano-2025-08-07", "o3", "o3-mini", "o4-mini");
    private static final Set<String> DEEPSEEK_EFFORT = Set.of(
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp");
    private final Map<String, Entry> entries;

    private ModelCatalog(Map<String, Entry> entries) { this.entries = entries; }

    static ModelCatalog load(Context context) {
        var cache = SereinUpdates.catalogFile(context);
        if (cache.getBaseFile().exists()) try (InputStream input = cache.openRead()) {
            return parse(input.readNBytes(MAX_BYTES + 1));
        } catch (IOException | JSONException error) {
            Log.w("Serein", "Cached model catalog unavailable", error);
        }
        try (InputStream input = context.getAssets().open("model-catalog.json")) {
            return parse(input.readNBytes(MAX_BYTES + 1));
        } catch (IOException | JSONException error) {
            Log.w("Serein", "Bundled model catalog unavailable", error);
            return new ModelCatalog(Map.of());
        }
    }

    static ModelCatalog parse(byte[] data) throws JSONException {
        if (data.length > MAX_BYTES) throw new JSONException("Model catalog too large");
        JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        if (root.getInt("schema") != 1) throw new JSONException("Unknown model catalog schema");
        JSONObject models = root.getJSONObject("models");
        if (models.length() == 0 || models.length() > 5_000) throw new JSONException("Invalid model count");
        Map<String, Entry> entries = new HashMap<>();
        var keys = models.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject row = models.getJSONObject(id);
            int tier = switch (row.optString("tier")) { case "standard" -> 0; case "flagship" -> 2; default -> 1; };
            String label = row.getString("label");
            String publisher = row.getString("publisher");
            String icon = row.getString("icon");
            int inputLimit = row.optInt("inputLimit");
            double price = row.optDouble("price", Double.POSITIVE_INFINITY);
            if (id.isBlank() || id.length() > 256 || label.length() > 256 || publisher.length() > 128 || icon.length() > 80
                    || inputLimit < 0 || price < 0) throw new JSONException("Invalid model metadata");
            entries.put(id, new Entry(label, publisher, icon, inputLimit, tier, price,
                    row.optBoolean("routeDisabled"), row.optBoolean("supportsTools")));
        }
        return new ModelCatalog(Map.copyOf(entries));
    }

    Entry find(String id) { return entries.get(id); }

    Providers.Model defaults(String id, String base) {
        Entry entry = find(id);
        return new Providers.Model(id, entry == null ? "none" : reasoning(id, base),
                entry == null ? "" : entry.label(), entry == null ? "" : entry.publisher(), "");
    }

    int icon(Providers.Model model) {
        if (!model.icon().isEmpty()) return ModelIcons.resolve(model.icon());
        Entry entry = find(model.id());
        return entry == null ? R.drawable.ic_cpu : ModelIcons.resolve(entry.icon());
    }

    private static String reasoning(String id, String base) {
        // The same model ID can be served by a gateway that accepts different parameters.
        String host = URI.create(base).getHost();
        if ("api.openai.com".equalsIgnoreCase(host) && OPENAI_EFFORT.contains(id)) return "effort";
        if ("api.deepseek.com".equalsIgnoreCase(host) && DEEPSEEK_EFFORT.contains(id)) return "deepseek";
        return "none";
    }
}
