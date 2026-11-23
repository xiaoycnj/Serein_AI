package app.serein.chat;

import android.content.res.AssetManager;
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
    record Entry(String label, String publisher, String icon, int inputLimit, int tier, double price,
                 boolean routeDisabled, boolean supportsTools) {}

    private static final Set<String> OPENAI_EFFORT = Set.of(
            "gpt-5", "gpt-5-2025-08-07", "gpt-5-mini", "gpt-5-mini-2025-08-07",
            "gpt-5-nano", "gpt-5-nano-2025-08-07", "o3", "o3-mini", "o4-mini");
    private static final Set<String> DEEPSEEK_EFFORT = Set.of(
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp");
    private final Map<String, Entry> entries;

    private ModelCatalog(Map<String, Entry> entries) { this.entries = entries; }

    static ModelCatalog load(AssetManager assets) {
        try (InputStream input = assets.open("model-catalog.json")) {
            JSONObject root = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (root.getInt("schema") != 1) throw new JSONException("Unknown model catalog schema");
            JSONObject models = root.getJSONObject("models");
            Map<String, Entry> entries = new HashMap<>();
            var keys = models.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject row = models.getJSONObject(id);
                int tier = switch (row.optString("tier")) { case "standard" -> 0; case "flagship" -> 2; default -> 1; };
                entries.put(id, new Entry(row.getString("label"), row.getString("publisher"), row.getString("icon"),
                        row.optInt("inputLimit"), tier, row.optDouble("price", Double.POSITIVE_INFINITY),
                        row.optBoolean("routeDisabled"), row.optBoolean("supportsTools")));
            }
            return new ModelCatalog(Map.copyOf(entries));
        } catch (IOException | JSONException error) {
            Log.w("Serein", "Bundled model catalog unavailable", error);
            return new ModelCatalog(Map.of());
        }
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
