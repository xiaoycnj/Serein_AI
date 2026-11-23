package app.serein.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// In-memory adaptation of AstraAgent's session_search.py (MIT).
final class ChatSearch {
    static final int QUERY_LIMIT = 120;
    static final int RESULT_LIMIT = 50;

    record Entry(int id, ChatHistory.Turn turn) {}
    record Source(ChatStore.Chat chat, String title, boolean archived, List<Entry> turns) {}
    record Hit(Source source, int id, boolean answer, String excerpt) {}
    record Results(List<Hit> hits, boolean limited) {}
    private record Candidate(int id, boolean answer, String text, String normalized, long bits) {}

    static List<Source> snapshot(List<ChatStore.Chat> chats) {
        List<Source> data = new ArrayList<>(chats.size());
        for (ChatStore.Chat chat : chats) {
            List<Entry> turns = new ArrayList<>();
            for (int id : chat.history.path(chat.history.leaf())) turns.add(new Entry(id, chat.history.turn(id)));
            data.add(new Source(chat, chat.title, chat.archived, List.copyOf(turns)));
        }
        return List.copyOf(data);
    }

    static Results find(List<Source> sources, String query, boolean includeArchived) {
        String normalized = normalize(query).replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (normalized.isBlank()) return new Results(List.of(), false);
        String[] terms = normalized.split(" ");
        // Bound query cost even when normalization expands characters.
        if (terms.length > 12) terms = java.util.Arrays.copyOf(terms, 12);
        long all = (1L << terms.length) - 1;
        List<Hit> results = new ArrayList<>();
        for (Source source : sources) {
            if (Thread.currentThread().isInterrupted()) return new Results(List.of(), false);
            if (source.archived() && !includeArchived) continue;
            long covered = bits(normalize(source.title()), terms);
            Candidate best = null;
            for (int i = source.turns().size() - 1; i >= 0; i--) {
                if (Thread.currentThread().isInterrupted()) return new Results(List.of(), false);
                Entry entry = source.turns().get(i);
                StringBuilder input = new StringBuilder(entry.turn().prompt());
                for (ChatFile file : entry.turn().attachments()) input.append('\n').append(file.name());
                Candidate question = candidate(entry.id(), false, input.toString(), terms);
                Candidate answer = candidate(entry.id(), true, entry.turn().assistantText(), terms);
                covered |= question.bits() | answer.bits();
                if (question.bits() != 0 && better(question, best)) best = question;
                if (answer.bits() != 0 && better(answer, best)) best = answer;
                if (best != null && best.bits() == all) break;
            }
            if (covered != all) continue;
            if (results.size() == RESULT_LIMIT) return new Results(List.copyOf(results), true);
            results.add(best == null ? new Hit(source, -1, false, "") : new Hit(source, best.id(),
                    best.answer(), excerpt(best.text(), best.normalized(), terms)));
        }
        return new Results(List.copyOf(results), false);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static Candidate candidate(int id, boolean answer, String text, String[] terms) {
        String normalized = normalize(text);
        return new Candidate(id, answer, text, normalized, bits(normalized, terms));
    }

    private static long bits(String text, String[] terms) {
        long result = 0;
        for (int i = 0; i < terms.length; i++) if (text.contains(terms[i])) result |= 1L << i;
        return result;
    }

    private static boolean better(Candidate next, Candidate best) {
        return best == null || Long.bitCount(next.bits()) > Long.bitCount(best.bits());
    }

    private static String excerpt(String original, String normalized, String[] terms) {
        int match = normalized.length();
        for (String term : terms) {
            int index = normalized.indexOf(term);
            if (index >= 0) match = Math.min(match, index);
        }
        int expected = Math.min(match, original.length());
        String excerpt = window(original, expected);
        if (bits(normalize(excerpt), terms) != 0) return excerpt;
        // NFKC can move offsets (for example when ligatures or fullwidth text expand).
        return window(normalized, match);
    }

    private static String window(String text, int offset) {
        int start = Math.max(0, offset - 24), end = Math.min(text.length(), start + 144);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end++;
        return (start == 0 ? "" : "…") + text.substring(start, end).replaceAll("[\\p{Z}\\s]+", " ").strip()
                + (end == text.length() ? "" : "…");
    }
}
