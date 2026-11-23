package app.serein.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class ReferenceSearch {
    static final int QUERY_LIMIT = 160;
    private static final Pattern WORD = Pattern.compile("\\p{sc=Han}+|[\\p{L}\\p{N}&&[^\\p{sc=Han}]]+");

    static String clip(String value, int limit) {
        if (limit <= 0) return "";
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    record Match(int score, int offset) {}

    static final class Query {
        private final String phrase;
        private final List<String> terms;

        Query(String raw) {
            phrase = normalize(clip(raw.strip(), QUERY_LIMIT));
            var words = WORD.matcher(phrase);
            var unique = new LinkedHashSet<String>();
            while (words.find() && unique.size() < 16) {
                String word = words.group();
                if (word.length() > 1) unique.add(word);
                if (word.length() > 2 && word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                    int[] points = word.codePoints().toArray();
                    for (int i = 0; i < points.length - 1 && unique.size() < 16; i++) unique.add(new String(points, i, 2));
                }
            }
            terms = new ArrayList<>(unique);
        }

        Match match(String value) {
            if (phrase.isBlank()) return new Match(0, 0);
            String normalized = normalize(value);
            int exact = normalized.indexOf(phrase);
            if (exact >= 0) return new Match(32, originalOffset(value, normalized, exact));
            int score = 0;
            int offset = normalized.length();
            int strongest = 0;
            for (String term : terms) {
                int found = normalized.indexOf(term);
                if (found < 0) continue;
                int weight = Math.min(12, term.length());
                score += weight;
                if (weight > strongest || (weight == strongest && found < offset)) {
                    strongest = weight;
                    offset = found;
                }
            }
            return new Match(Math.min(score, 31), score == 0 ? 0 : originalOffset(value, normalized, offset));
        }
    }

    private static int originalOffset(String original, String normalized, int offset) {
        if (offset == 0 || original.length() == normalized.length()) return offset;
        int low = 0;
        int high = original.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (normalize(original.substring(0, middle)).length() <= offset) low = middle;
            else high = middle - 1;
        }
        return low > 0 && low < original.length() && Character.isLowSurrogate(original.charAt(low)) ? low - 1 : low;
    }

    static String excerpt(String text, int offset, int limit) {
        if (text.isEmpty()) return "";
        int start = Math.max(0, Math.min(text.length() - limit, offset - limit / 4));
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        String excerpt = clip(text.substring(start), limit).strip();
        return (start > 0 ? "…\n" : "") + excerpt + (text.length() - start > limit ? "\n…" : "");
    }
}
