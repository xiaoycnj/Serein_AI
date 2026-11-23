package app.serein.chat;

import java.util.List;

// An unsaved attempt belongs to the selected conversation and version, not to its prompt text.
record ReplyRetry(ChatStore.Chat chat, int leaf, int parent, String prompt, boolean fromComposer, String searchQuery,
                  List<ChatFile> attachments) {
    ReplyRetry {
        attachments = List.copyOf(attachments);
    }

    ReplyRetry(ChatStore.Chat chat, int parent, String prompt, boolean fromComposer) {
        this(chat, parent, prompt, fromComposer, null);
    }

    ReplyRetry(ChatStore.Chat chat, int parent, String prompt, boolean fromComposer, String searchQuery) {
        this(chat, parent, prompt, fromComposer, searchQuery, List.of());
    }

    ReplyRetry(ChatStore.Chat chat, int parent, String prompt, boolean fromComposer, String searchQuery,
               List<ChatFile> attachments) {
        this(chat, leafOf(chat), parent, prompt, fromComposer, searchQuery, attachments);
    }

    boolean matches(ChatStore.Chat active) {
        return chat == active && leaf == leafOf(active);
    }

    boolean consumesDraft(String draft) {
        return fromComposer && prompt.equals(draft);
    }

    boolean consumesDraft(String draft, List<ChatFile> files) {
        return consumesDraft(draft) && attachments.equals(files);
    }

    private static int leafOf(ChatStore.Chat chat) {
        return chat == null ? -1 : chat.history.leaf();
    }
}
