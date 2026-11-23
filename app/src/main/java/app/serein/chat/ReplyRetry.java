package app.serein.chat;

// An unsaved attempt belongs to the selected conversation and version, not to its prompt text.
record ReplyRetry(ChatStore.Chat chat, int leaf, int parent, String prompt, boolean fromComposer, String searchQuery) {
    ReplyRetry(ChatStore.Chat chat, int parent, String prompt, boolean fromComposer) {
        this(chat, parent, prompt, fromComposer, null);
    }

    ReplyRetry(ChatStore.Chat chat, int parent, String prompt, boolean fromComposer, String searchQuery) {
        this(chat, leafOf(chat), parent, prompt, fromComposer, searchQuery);
    }

    boolean matches(ChatStore.Chat active) {
        return chat == active && leaf == leafOf(active);
    }

    boolean consumesDraft(String draft) {
        return fromComposer && prompt.equals(draft);
    }

    private static int leafOf(ChatStore.Chat chat) {
        return chat == null ? -1 : chat.history.leaf();
    }
}
