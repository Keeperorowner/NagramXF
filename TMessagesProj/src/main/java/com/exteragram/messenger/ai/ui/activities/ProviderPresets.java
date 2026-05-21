package com.exteragram.messenger.ai.ui.activities;

public final class ProviderPresets {

    public static final int PRESET_CUSTOM = 0;
    public static final int PRESET_OPENAI = 1;
    public static final int PRESET_GEMINI = 2;
    public static final int PRESET_CLAUDE = 3;
    public static final int PRESET_DEEPSEEK = 4;
    public static final int PRESET_GROQ = 5;
    public static final int PRESET_XAI = 6;
    public static final int PRESET_OPENROUTER = 7;

    public static final String[] PRESET_NAMES = {"Custom", "OpenAI", "Gemini", "Claude", "DeepSeek", "Groq", "xAI", "OpenRouter"};
    public static final String[] PRESET_URLS = {
            "",
            "https://api.openai.com/v1",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://api.anthropic.com/v1",
            "https://api.deepseek.com/v1",
            "https://api.groq.com/openai/v1",
            "https://api.x.ai/v1",
            "https://openrouter.ai/api/v1"
    };
    public static final String[] PRESET_DEFAULT_MODELS = {"", "gpt-4o", "gemini-2.5-flash", "claude-sonnet-4-20250514", "deepseek-chat", "llama-3.3-70b-versatile", "grok-3-mini", "openai/gpt-4o"};

    private ProviderPresets() {}
}
