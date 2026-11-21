package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Providers {
    record Key(String label, String secret) {}
    record Model(String id, String reasoning, String label, String publisher, String icon) {
        String title() { return label.isEmpty() ? id : label; }
    }
    record Choice(Provider provider, Model model) {}

    static final class Provider {
        final String id;
        final String label;
        final String base;
        final List<Key> keys;
        final List<Model> models;
        final int activeKey;

        Provider(String id, String label, String base, List<Key> keys, List<Model> models, int activeKey) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            this.label = label;
            this.base = base;
            this.keys = List.copyOf(keys);
            this.models = List.copyOf(models);
            this.activeKey = keys.isEmpty() ? -1 : Math.max(0, Math.min(activeKey, keys.size() - 1));
        }

        String secret() { return activeKey < 0 ? "" : keys.get(activeKey).secret(); }
        boolean anthropicMessages() {
            URI uri = URI.create(base);
            return "api.anthropic.com".equalsIgnoreCase(uri.getHost()) && "/v1".equals(uri.getRawPath());
        }
        Provider credentials(List<Key> updated, int selected) { return new Provider(id, label, base, updated, models, selected); }
        Provider catalog(List<Model> updated) { return new Provider(id, label, base, keys, updated, activeKey); }
    }

    final List<Provider> all = new ArrayList<>();
    private final Vault vault;
    private String selectedProvider = "";
    private String selectedModel = "";

    Providers(Vault vault, ModelCatalog modelCatalog) {
        this.vault = vault;
        try {
            JSONObject data = new JSONObject(vault.read("providers", "{\"providers\":[]}"));
            JSONArray providers = data.getJSONArray("providers");
            for (int i = 0; i < providers.length(); i++) {
                JSONObject item = providers.getJSONObject(i);
                List<Key> keys = new ArrayList<>();
                JSONArray storedKeys = item.getJSONArray("keys");
                for (int j = 0; j < storedKeys.length(); j++) {
                    JSONObject key = storedKeys.getJSONObject(j);
                    keys.add(new Key(key.getString("name"), key.getString("secret")));
                }
                List<Model> models = new ArrayList<>();
                JSONArray catalog = item.getJSONArray("models");
                for (int j = 0; j < catalog.length(); j++) {
                    JSONObject model = catalog.getJSONObject(j);
                    String id = model.getString("id");
                    ModelCatalog.Entry entry = modelCatalog.find(id);
                    models.add(new Model(id, model.optString("reasoning", "none"),
                            model.optString("label", entry == null ? "" : entry.label()),
                            model.optString("publisher", entry == null ? "" : entry.publisher()),
                            model.optString("icon", "")));
                }
                all.add(new Provider(item.getString("id"), item.getString("label"), item.getString("base"),
                        keys, models, item.optInt("activeKey")));
            }
            selectedProvider = data.optString("selectedProvider");
            selectedModel = data.optString("selectedModel");
        } catch (JSONException error) {
            throw new IllegalStateException("无法读取服务商配置", error);
        }
    }

    static String httpsBase(String value) {
        try {
            URI uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return uri.toASCIIString().replaceFirst("/+$", "");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("请输入 HTTPS 接口根地址，不含查询参数或认证信息。");
        }
    }

    Provider get(String id) {
        for (Provider provider : all) if (provider.id.equals(id)) return provider;
        return null;
    }

    Choice choice() {
        Provider provider = get(selectedProvider);
        if (provider != null) for (Model model : provider.models) {
            if (model.id().equals(selectedModel)) return new Choice(provider, model);
        }
        return null;
    }

    void select(Provider provider, Model model) {
        update(new ArrayList<>(all), provider == null ? "" : provider.id, model == null ? "" : model.id());
    }

    void put(Provider provider) {
        put(provider, "", "");
    }

    void put(Provider provider, String previousModelId, String newModelId) {
        List<Provider> next = new ArrayList<>(all);
        int index = next.indexOf(get(provider.id));
        if (index < 0) next.add(provider);
        else next.set(index, provider);
        boolean activeProvider = provider.id.equals(selectedProvider);
        String activeModel = activeProvider && previousModelId.equals(selectedModel) ? newModelId : selectedModel;
        boolean selectedRemoved = activeProvider
                && provider.models.stream().noneMatch(model -> model.id().equals(activeModel));
        update(next, selectedRemoved ? "" : selectedProvider, selectedRemoved ? "" : activeModel);
    }

    void remove(Provider provider) {
        List<Provider> next = new ArrayList<>(all);
        next.removeIf(item -> item.id.equals(provider.id));
        update(next, selectedProvider.equals(provider.id) ? "" : selectedProvider,
                selectedProvider.equals(provider.id) ? "" : selectedModel);
    }

    private void update(List<Provider> next, String providerId, String modelId) {
        try {
            JSONArray list = new JSONArray();
            for (Provider provider : next) {
                JSONArray keys = new JSONArray();
                for (Key key : provider.keys) keys.put(new JSONObject().put("name", key.label()).put("secret", key.secret()));
                JSONArray models = new JSONArray();
                for (Model model : provider.models) models.put(new JSONObject().put("id", model.id())
                        .put("reasoning", model.reasoning()).put("label", model.label()).put("publisher", model.publisher())
                        .put("icon", model.icon()));
                list.put(new JSONObject().put("id", provider.id).put("label", provider.label).put("base", provider.base)
                        .put("activeKey", provider.activeKey).put("keys", keys).put("models", models));
            }
            vault.write("providers", new JSONObject().put("providers", list)
                    .put("selectedProvider", providerId).put("selectedModel", modelId).toString());
        } catch (JSONException error) {
            throw new IllegalStateException("无法保存服务商配置", error);
        }
        all.clear();
        all.addAll(next);
        selectedProvider = providerId;
        selectedModel = modelId;
    }
}
