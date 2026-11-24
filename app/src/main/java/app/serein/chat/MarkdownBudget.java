package app.serein.chat;

final class MarkdownBudget {
    private static final int MAX_CHARS = 24 * 1024;
    private static final int MAX_BREAKS = 512;
    private static final int MAX_MARKERS = 2048;
    private static final int HEAD_CHARS = 6144, TAIL_CHARS = 2048;
    private static final int HEAD_BREAKS = 120, TAIL_BREAKS = 40;

    static boolean requiresPreview(String source) {
        if (source.length() > MAX_CHARS) return true;
        int markers = 0, breaks = 0;
        for (int i = 0; i < source.length(); i++) {
            switch (source.charAt(i)) {
                case '\r', '\n' -> { if (++breaks > MAX_BREAKS) return true; }
                case '*', '_', '`', '~', '[', '|', '>' -> { if (++markers > MAX_MARKERS) return true; }
            }
        }
        return false;
    }

    static String preview(String source) {
        int head = Math.min(source.length(), HEAD_CHARS);
        int breaks = 0;
        for (int i = 0; i < head; i++) {
            if (lineBreak(source.charAt(i)) && ++breaks >= HEAD_BREAKS) { head = i; break; }
        }
        int tail = Math.max(0, source.length() - TAIL_CHARS);
        breaks = 0;
        for (int i = source.length() - 1; i >= tail; i--) {
            if (lineBreak(source.charAt(i)) && ++breaks >= TAIL_BREAKS) { tail = i + 1; break; }
        }
        if (head >= tail) return source;
        return source.substring(0, ChatArtifacts.boundary(source, head)) + "\n\n…\n\n"
                + source.substring(ChatArtifacts.boundary(source, tail));
    }

    private static boolean lineBreak(char c) { return c == '\r' || c == '\n'; }
}
