package com.termux.app.ai;

import androidx.annotation.NonNull;

import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class AIModelManager {

    private static final List<AIModelInfo> DEFAULT_BUILTIN_MODELS = Arrays.asList(
        new AIModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash (Recommended · No Key)", AIProviderType.BUILTIN, true, 1048576),
        new AIModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (Ultra Fast · No Key)", AIProviderType.BUILTIN, true, 1048576),
        new AIModelInfo("gemini-4-argon", "Gemini 4 Argon (Advanced · Blaze)", AIProviderType.BUILTIN, false, 1048576)
    );

    private static final List<AIModelInfo> DEFAULT_GEMINI_MODELS = Arrays.asList(
        new AIModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash (Recommended)", AIProviderType.GEMINI, false, 1048576),
        new AIModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", AIProviderType.GEMINI, false, 1048576),
        new AIModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", AIProviderType.GEMINI, false, 1048576)
    );

    private static final List<AIModelInfo> DEFAULT_OPENROUTER_MODELS = Arrays.asList(
        new AIModelInfo("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B Instruct", AIProviderType.OPENROUTER, true, 131072),
        new AIModelInfo("qwen/qwen-2.5-coder-32b-instruct:free", "Qwen 2.5 Coder 32B Instruct", AIProviderType.OPENROUTER, true, 32768),
        new AIModelInfo("deepseek/deepseek-chat-v3-0324:free", "DeepSeek V3 0324", AIProviderType.OPENROUTER, true, 163840),
        new AIModelInfo("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash Exp", AIProviderType.OPENROUTER, true, 1048576),
        new AIModelInfo("openai/gpt-4o-mini", "GPT-4o Mini", AIProviderType.OPENROUTER, false, 128000),
        new AIModelInfo("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", AIProviderType.OPENROUTER, false, 200000)
    );

    private final Map<AIProviderType, List<AIModelInfo>> mFetchedCache = new EnumMap<>(AIProviderType.class);

    @NonNull
    public List<AIModelInfo> getBuiltInModels(@NonNull AIProviderType provider) {
        if (provider == AIProviderType.BUILTIN) {
            return Collections.unmodifiableList(DEFAULT_BUILTIN_MODELS);
        }
        if (provider == AIProviderType.OPENROUTER) {
            return Collections.unmodifiableList(DEFAULT_OPENROUTER_MODELS);
        }
        return Collections.unmodifiableList(DEFAULT_GEMINI_MODELS);
    }

    public synchronized void updateFetchedModels(@NonNull AIProviderType provider, @NonNull List<AIModelInfo> fetched) {
        mFetchedCache.put(provider, new ArrayList<>(fetched));
    }

    @NonNull
    public synchronized List<AIModelInfo> getModelsForProvider(@NonNull AIProviderType provider) {
        List<AIModelInfo> cached = mFetchedCache.get(provider);
        if (cached != null && !cached.isEmpty()) {
            return Collections.unmodifiableList(cached);
        }
        return getBuiltInModels(provider);
    }
}
