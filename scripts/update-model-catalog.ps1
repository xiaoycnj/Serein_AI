param([Parameter(Mandatory)][string]$Source)

$ErrorActionPreference = 'Stop'
$revision = 'ac7674625ceed298d6c411cc964f23aded63989d'
$sha256 = 'F410A08E6D566829E3A858B00E8B23F5A796FD2360F056A69CAB43526F908864'
if ((Get-FileHash -LiteralPath $Source).Hash -ne $sha256) { throw 'Unexpected LiteLLM snapshot.' }
$sourceData = Get-Content -LiteralPath $Source -Raw | ConvertFrom-Json -AsHashtable

# Classify verified IDs from the source snapshot. Runtime lookups use full, literal keys.
$families = @(
    @('openai', 'OpenAI', 'openai', '^(gpt-|o[134](?:-|$)|chatgpt-)'),
    @('anthropic', 'Anthropic', 'claude', '^claude-'),
    @('gemini|vertex_ai-language-models', 'Google', 'gemini', '^(gemini-|gemma-)'),
    @('deepseek', 'DeepSeek', 'deepseek', '^deepseek-'),
    @('dashscope|qwen_ai_platform|qwencloud', 'Alibaba', 'qwen', '^(qwen|qwq-|qvq-)'),
    @('xai', 'xAI', 'grok', '^grok-'),
    @('moonshot', 'Moonshot AI', 'kimi', '^(kimi-|moonshot-)'),
    @('mistral', 'Mistral AI', 'mistral', '^(mistral-|mixtral-|ministral-|codestral-|magistral-|devstral-|pixtral-|voxtral-)'),
    @('meta_llama', 'Meta', 'meta', '^llama-'),
    @('minimax', 'MiniMax', 'minimax', '^(minimax-|abab)'),
    @('perplexity', 'Perplexity', 'perplexity', '^(sonar(?:-|$)|r1-1776$)'),
    @('volcengine', 'ByteDance', 'doubao', '^doubao-'),
    @('openrouter', 'DeepSeek', 'deepseek', '^deepseek/deepseek-')
)
$names = @{
    'gpt-5' = 'GPT-5'; 'gpt-5-mini' = 'GPT-5 mini'; 'gpt-5-nano' = 'GPT-5 nano'
    'gpt-4o' = 'GPT-4o'; 'gpt-4o-mini' = 'GPT-4o mini'
    'deepseek-chat' = 'DeepSeek Chat'; 'deepseek-reasoner' = 'DeepSeek Reasoner'
    'deepseek-v4-pro' = 'DeepSeek V4 Pro'; 'deepseek-v4-flash' = 'DeepSeek V4 Flash'
    'claude-sonnet-4-6' = 'Claude Sonnet 4.6'; 'claude-opus-4-6' = 'Claude Opus 4.6'
    'gemini-2.5-pro' = 'Gemini 2.5 Pro'; 'gemini-2.5-flash' = 'Gemini 2.5 Flash'
    'qwen3-max' = 'Qwen3 Max'; 'kimi-k2.5' = 'Kimi K2.5'; 'kimi-k2.6' = 'Kimi K2.6'
}
$routerNamespaces = @{
    'openai' = 'openai'; 'claude' = 'anthropic'; 'gemini' = 'google'; 'deepseek' = 'deepseek'
    'qwen' = 'qwen'; 'grok' = 'x-ai'; 'kimi' = 'moonshotai'; 'mistral' = 'mistralai'
    'meta' = 'meta-llama'; 'minimax' = 'minimax'; 'perplexity' = 'perplexity'
}
$entries = [System.Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
foreach ($entry in $sourceData.GetEnumerator()) {
    $apiId = [string]$entry.Key
    $meta = $entry.Value
    if ($meta -isnot [System.Collections.IDictionary] -or $meta['mode'] -ne 'chat') { continue }
    $provider = $meta['litellm_provider']
    if ($provider -and $apiId.StartsWith("$provider/", [StringComparison]::Ordinal)) {
        $apiId = $apiId.Substring($provider.Length + 1)
    }
    foreach ($family in $families) {
        if ($provider -notmatch "^(?:$($family[0]))$" -or $apiId -notmatch $family[3]) { continue }
        $leaf = ($apiId -split '/')[-1]
        $idList = @($apiId, [string]$entry.Key)
        $namespace = $routerNamespaces[$family[2]]
        # A namespaced ID must also be present in the pinned data or the explicit alias list below.
        if ($namespace -and $sourceData.Contains("openrouter/$namespace/$leaf")) {
            $idList += "$namespace/$leaf", "openrouter/$namespace/$leaf"
        }
        $record = [ordered]@{label = $names[$leaf] ?? $leaf; publisher = $family[1]; icon = $family[2]}
        foreach ($id in $idList) { $entries[$id] = $record }
        break
    }
}

# Verified gateway aliases differ from the upstream ID by more than a provider prefix.
$aliases = @{
    'openai/gpt-5-mini' = 'gpt-5-mini'; 'openai/gpt-4o-mini' = 'gpt-4o-mini'
    'anthropic/claude-sonnet-4.6' = 'claude-sonnet-4-6'; 'anthropic/claude-opus-4.6' = 'claude-opus-4-6'
    'google/gemini-2.5-pro' = 'gemini-2.5-pro'; 'google/gemini-2.5-flash' = 'gemini-2.5-flash'
    'deepseek/deepseek-r1' = 'deepseek-r1'; 'deepseek/deepseek-chat' = 'deepseek-chat'
}
foreach ($alias in $aliases.Keys) {
    $target = $aliases[$alias]
    if (!$entries.ContainsKey($target)) { throw "Alias $alias points to missing model $target." }
    $entries[$alias] = $entries[$target]
}
$asset = Join-Path (Split-Path -Parent $PSScriptRoot) 'app/src/main/assets/model-catalog.json'
$data = [ordered]@{schema = 1; revision = $revision; models = $entries}
$data | ConvertTo-Json -Depth 6 -Compress | Set-Content -LiteralPath $asset -Encoding utf8NoBOM
Write-Output "$($entries.Count) exact model IDs written to $asset"
