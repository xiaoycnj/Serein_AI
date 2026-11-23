package app.serein.chat;

import java.util.HashSet;
import java.util.List;

final class ChatDeliveryCheck {
    public static void main(String[] args) {
        var provider = provider("chat", "My Chat", "https://chat.example.test/v1");
        var chat = ChatDelivery.Destination.chat(provider);
        var search = ChatDelivery.Destination.search(provider("search", "My Search", "https://api.deepseek.com"));
        var images = ChatDelivery.Destination.image(provider("images", "My Images", "https://art.example.test/v1"));
        var reconfigured = ChatDelivery.Destination.image(provider("images", "My Images", "https://another.example.test/v1"));

        var plain = new ChatDelivery(chat, null, null, false, false, false, false, false, false);
        var scopes = List.of(plain,
                new ChatDelivery(chat, search, null, false, false, false, false, false, false),
                new ChatDelivery(chat, search, null, true, false, false, false, false, false),
                new ChatDelivery(chat, null, images, false, false, false, false, false, false),
                new ChatDelivery(chat, null, reconfigured, false, false, false, false, false, false),
                new ChatDelivery(chat, null, null, false, true, false, false, false, false),
                new ChatDelivery(chat, null, null, false, false, true, false, false, false),
                new ChatDelivery(chat, null, null, false, false, false, true, false, false),
                new ChatDelivery(chat, null, null, false, false, false, false, true, false),
                new ChatDelivery(chat, null, null, false, false, false, false, false, true));
        var keys = new HashSet<String>();
        for (var scope : scopes) {
            require(keys.add(scope.preferenceKey()), "each destination and data scope requires its own confirmation");
            require(ChatDelivery.isConfirmation(scope.preferenceKey()), "saved scope can be revoked");
        }
        var renamed = ChatDelivery.Destination.chat(provider("chat", "Renamed Chat", provider.base));
        require(plain.preferenceKey().equals(new ChatDelivery(renamed, null, null, false,
                false, false, false, false, false).preferenceKey()), "renaming a destination preserves its confirmation");
        require(ChatDelivery.isConfirmation("consent_extensions_legacy"), "revocation includes pre-upgrade confirmations");
        require(!ChatDelivery.isConfirmation("chat_web"), "revocation keeps extension settings");

        var anthropic = ChatDelivery.Destination.chat(provider("anthropic", "Claude", "https://api.anthropic.com/v1"));
        require(anthropic.endpoint().equals("https://api.anthropic.com/v1/messages"), "native Messages destination");
        var combined = new ChatDelivery(chat, search, images, false, true, true, true, true, true);
        String description = combined.description();
        for (String value : List.of(chat.endpoint(), WebSearch.ENDPOINT, images.endpoint(), "附件", "资料库", "记忆", "账户", "摘要")) {
            require(description.contains(value), "disclosure includes " + value);
        }
        require(!description.contains("synthetic-secret"), "the disclosure excludes API keys");
        System.out.println("PASS: chat delivery discloses each endpoint and scopes confirmation to outbound data and recipients");
    }

    private static Providers.Provider provider(String id, String label, String base) {
        return new Providers.Provider(id, label, base, List.of(new Providers.Key("Demo Key", "synthetic-secret")), List.of(), 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
