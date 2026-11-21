package app.serein.chat;

import java.util.List;

final class ModelIcons {
    record Option(String id, String label, int glyph) {}

    static final List<Option> OPTIONS = List.of(
            new Option("", "跟随模型目录", R.drawable.ic_cpu),
            new Option("generic", "通用模型", R.drawable.ic_cpu),
            new Option("openai", "OpenAI", R.drawable.brand_openai),
            new Option("claude", "Anthropic", R.drawable.brand_claude),
            new Option("gemini", "Google", R.drawable.brand_gemini),
            new Option("deepseek", "DeepSeek", R.drawable.brand_deepseek),
            new Option("qwen", "Qwen", R.drawable.brand_qwen),
            new Option("grok", "xAI", R.drawable.brand_grok),
            new Option("kimi", "Kimi", R.drawable.brand_kimi),
            new Option("mistral", "Mistral AI", R.drawable.brand_mistral),
            new Option("meta", "Meta", R.drawable.brand_meta),
            new Option("minimax", "MiniMax", R.drawable.brand_minimax),
            new Option("perplexity", "Perplexity", R.drawable.brand_perplexity),
            new Option("doubao", "Doubao", R.drawable.brand_doubao),
            new Option("baidu", "Baidu", R.drawable.brand_baidu),
            new Option("yi", "01.AI", R.drawable.brand_yi),
            new Option("ollama", "Ollama", R.drawable.brand_ollama));

    static Option option(String id) {
        for (Option option : OPTIONS) if (option.id().equals(id)) return option;
        return OPTIONS.get(1);
    }

    static int resolve(String id) { return option(id).glyph(); }
}
