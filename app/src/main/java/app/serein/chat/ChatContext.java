package app.serein.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class ChatContext {
    record Checkpoint(String scope, int through, String text) {}
    record Context(List<ChatHistory.Turn> recent, String summary, Checkpoint checkpoint) {}
    record ModelSpec(String id, int inputLimit, int tier, double price) {}

    private static final int KEEP_TURNS = 4;
    private static final int CHUNK_CHARS = 12_000;
    private static final int MAX_CHUNKS = 12;
    static final int MAX_SUMMARY_CHARS = 3_000;
    private static final Pattern COMPLEX = Pattern.compile("全面|复杂|深入|系统设计|证明|科研|architecture|comprehensive", Pattern.CASE_INSENSITIVE);
    private static final Pattern TECHNICAL = Pattern.compile("代码|编程|审阅|审查|调试|架构|\\b(?:bug|code|debug|refactor)\\b", Pattern.CASE_INSENSITIVE);

    static int estimate(String text) {
        return (text.getBytes(StandardCharsets.UTF_8).length + 2) / 3 + 8;
    }

    static long estimate(List<ChatHistory.Turn> history, String profile, String prompt) {
        long total = estimate(profile) + estimate(prompt);
        for (ChatHistory.Turn turn : history) total += (long) estimate(turn.prompt()) + estimate(turn.assistantText());
        return total;
    }

    static int budget(int inputLimit) {
        return inputLimit > 0 ? (int) (inputLimit * .8) - Math.min(4096, inputLimit / 4) : 20_000;
    }

    static int threshold(int inputLimit) { return Math.min(budget(inputLimit), 32_000); }

    static ModelSpec route(List<ModelSpec> candidates, String prompt, long total, long recent, boolean compact) {
        int recommended = COMPLEX.matcher(prompt).find() ? 2 : TECHNICAL.matcher(prompt).find() || prompt.length() > 2000 ? 1 : 0;
        ModelSpec best = null;
        int bestGroup = Integer.MAX_VALUE;
        for (ModelSpec model : candidates) {
            if (model.inputLimit() <= 0) continue;
            int capacity = budget(model.inputLimit());
            boolean fits = total <= capacity;
            if (!fits && (!compact || recent + MAX_SUMMARY_CHARS + 32 > capacity)) continue;
            // Prefer a full transcript, then the smallest tier that meets the recommendation.
            int gap = model.tier() - recommended;
            int group = (fits ? 0 : 8) + (gap >= 0 ? gap : 3 - gap);
            if (best == null || group < bestGroup || (group == bestGroup && cheaper(model, best))) {
                best = model;
                bestGroup = group;
            }
        }
        return best;
    }

    private static boolean cheaper(ModelSpec a, ModelSpec b) {
        int byPrice = Double.compare(a.price(), b.price());
        return byPrice < 0 || (byPrice == 0 && a.id().compareTo(b.id()) < 0);
    }

    static List<ChatHistory.Turn> recent(List<ChatHistory.Turn> turns) {
        return turns.subList(Math.max(0, turns.size() - KEEP_TURNS), turns.size());
    }

    @FunctionalInterface
    interface Summarizer { String summarize(String previous, String fragment) throws Exception; }

    static Context prepare(List<ChatHistory.Turn> turns, List<Integer> path, String profile, String prompt,
                           int inputLimit, String scope, Checkpoint cached, Summarizer summarizer) throws Exception {
        int capacity = budget(inputLimit);
        long original = estimate(turns, profile, prompt);
        if (original <= threshold(inputLimit)) return new Context(turns, "", null);
        int end = Math.max(0, turns.size() - KEEP_TURNS);
        int previous = cached != null && cached.scope().equals(scope) ? path.indexOf(cached.through()) : -1;
        String summary = previous >= 0 && previous < end ? cached.text() : "";
        int start = summary.isEmpty() ? 0 : previous + 1;
        List<ChatHistory.Turn> recent = turns.subList(end, turns.size());
        long tail = estimate(recent, profile, prompt);
        if (end == 0 || tail + MAX_SUMMARY_CHARS + 32 > capacity) {
            if (original <= capacity) return new Context(turns, "", null);
            throw new IOException("模型的上下文空间不足以保留最近对话，请选择上下文更大的模型或新建聊天。");
        }
        int chunkChars = Math.min(CHUNK_CHARS, capacity - MAX_SUMMARY_CHARS - 512);
        if (chunkChars < 256) throw new IOException("模型没有足够的空间生成历史摘要。");
        for (String chunk : chunks(turns.subList(start, end), chunkChars)) {
            summary = summarizer.summarize(summary, chunk).strip();
            if (summary.isBlank() || summary.length() > MAX_SUMMARY_CHARS) {
                throw new IOException("服务商返回的上下文摘要为空或超长。");
            }
        }
        if (tail + estimate(summary) + 32 > capacity || tail + estimate(summary) + 32 >= original) {
            throw new IOException("模型生成的摘要未能缩短上下文，请选择上下文更大的模型。");
        }
        return new Context(recent, summary, new Checkpoint(scope, path.get(end - 1), summary));
    }

    private static List<String> chunks(List<ChatHistory.Turn> older, int limit) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder pending = new StringBuilder(limit);
        for (ChatHistory.Turn turn : older) {
            append(result, pending, "\nUser: ", limit);
            append(result, pending, turn.prompt(), limit);
            append(result, pending, "\nAssistant: ", limit);
            append(result, pending, turn.assistantText(), limit);
        }
        if (pending.length() > 0) result.add(pending.toString());
        if (result.size() > MAX_CHUNKS) throw new IOException("服务商待压缩的历史过长，请选择上下文更大的模型或新建聊天。");
        return result;
    }

    private static void append(List<String> chunks, StringBuilder pending, String text, int chunkChars) throws IOException {
        for (int start = 0; start < text.length();) {
            int limit = Math.min(text.length(), start + chunkChars - pending.length());
            if (limit > start && limit < text.length() && Character.isHighSurrogate(text.charAt(limit - 1))) limit--;
            pending.append(text, start, limit);
            if (limit < text.length() || pending.length() == chunkChars) {
                if (chunks.size() >= MAX_CHUNKS) throw new IOException("服务商待压缩的历史过长，请选择上下文更大的模型或新建聊天。");
                chunks.add(pending.toString());
                pending.setLength(0);
            }
            start = limit;
        }
    }
}
