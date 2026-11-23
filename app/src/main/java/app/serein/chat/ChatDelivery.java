package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Describes the outbound scope authorized before starting one online chat turn. */
record ChatDelivery(Destination chat, Destination search, Destination image, boolean explicitSearch,
                    boolean attachments, boolean library, boolean memory, boolean profile, boolean compaction) {
    private static final String PREFIX = "consent_delivery_v1_";

    record Destination(String id, String label, String endpoint) {
        static Destination chat(Providers.Provider provider) {
            return provider(provider, ModelApi.chatPath(provider));
        }

        static Destination image(Providers.Provider provider) { return provider(provider, ImageGeneration.PATH); }

        static Destination search(Providers.Provider provider) {
            return new Destination(provider.id, "DeepSeek Search", WebSearch.ENDPOINT);
        }

        private static Destination provider(Providers.Provider provider, String path) {
            return new Destination(provider.id, provider.label, Providers.httpsBase(provider.base) + path);
        }

        JSONArray scope() { return new JSONArray().put(id).put(endpoint); }
    }

    String preferenceKey() {
        var scope = new JSONArray().put(chat.scope())
                .put(search == null ? JSONObject.NULL : search.scope())
                .put(image == null ? JSONObject.NULL : image.scope())
                .put(explicitSearch).put(attachments).put(library).put(memory).put(profile).put(compaction);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.toString().getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    static boolean isConfirmation(String key) { return key.startsWith("consent_"); }

    String description() {
        var text = new StringBuilder(chat.label()).append('\n').append(chat.endpoint())
                .append("\n本轮消息及当前分支的对话上下文。");
        if (attachments) text.append("\n· 随附图片和相关附件摘录");
        if (library) text.append("\n· 检索命中的资料库摘录");
        if (memory) text.append("\n· 检索命中的本机记忆");
        if (profile) text.append("\n· 账户名称与简介");
        if (compaction) text.append("\n· 较早消息可能另行发送以生成摘要，并产生费用");
        if (search != null) {
            text.append("\n\n").append(search.label()).append('\n').append(search.endpoint())
                    .append(explicitSearch ? "\n已选检索词。" : "\n模型按需发送检索词。")
                    .append("搜索结果摘要会发给聊天服务商，搜索可能单独计费。");
        }
        if (image != null) {
            text.append("\n\n生图 · ").append(image.label()).append('\n').append(image.endpoint())
                    .append("\n模型按需发送图像描述，生图可能单独计费。");
        }
        return text.toString();
    }
}
