package app.serein.chat;

record HistoryWindow(int start, int end, int size) {
    static final int LIMIT = 32;
    static final int LATEST = -1;

    static HistoryWindow at(int size, int requestedStart) {
        int lastStart = Math.max(0, size - LIMIT);
        int start = requestedStart == LATEST ? lastStart : Math.max(0, Math.min(requestedStart, lastStart));
        return new HistoryWindow(start, Math.min(start + LIMIT, size), size);
    }

    static int around(int size, int index) {
        int start = Math.max(0, index - 6);
        return index >= size - LIMIT ? LATEST : start;
    }

    boolean includesLatest() { return end == size; }

    int older() { return Math.max(0, start - LIMIT); }

    int newer() { return end >= size - LIMIT ? LATEST : end; }
}
