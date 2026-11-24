package app.serein.chat;

final class HistoryWindowCheck {
    public static void main(String[] args) {
        shortChat();
        longChat();
        anchors();
        System.out.println("PASS: history windows cover every turn, keep anchors visible, and cap displayed history");
    }

    private static void shortChat() {
        verify(HistoryWindow.at(0, HistoryWindow.LATEST).includesLatest(), "Empty history");
        for (int size = 1; size <= HistoryWindow.LIMIT; size++) {
            var window = HistoryWindow.at(size, HistoryWindow.LATEST);
            verify(window.start() == 0 && window.end() == size && window.includesLatest(), "Short chat");
        }
    }

    private static void longChat() {
        for (int size : new int[]{33, 63, 64, 65, 1_200}) {
            var current = HistoryWindow.at(size, HistoryWindow.LATEST);
            verify(current.end() == size && current.end() - current.start() == HistoryWindow.LIMIT, "Latest window");
            while (current.start() > 0) {
                var previous = HistoryWindow.at(size, current.older());
                verify(previous.start() < current.start() && previous.end() >= current.start(), "Going back does not skip");
                current = previous;
            }
            while (!current.includesLatest()) {
                var next = HistoryWindow.at(size, current.newer());
                verify(next.start() > current.start() && next.start() <= current.end(), "Going forward does not skip");
                current = next;
            }
        }
    }

    private static void anchors() {
        int size = 1_200;
        for (int index = 0; index < size; index++) {
            var window = HistoryWindow.at(size, HistoryWindow.around(size, index));
            verify(index >= window.start() && index < window.end(), "Requested message must be visible");
            verify(window.end() - window.start() <= HistoryWindow.LIMIT, "Bounded window");
        }
        var truncated = HistoryWindow.at(11, 1_120);
        verify(truncated.start() == 0 && truncated.end() == 11, "Changing to a shorter message branch");
    }

    private static void verify(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
