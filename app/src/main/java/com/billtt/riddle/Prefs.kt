package com.billtt.riddle

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("riddle", Context.MODE_PRIVATE)

    /** Backend selection: PROVIDER_ANTHROPIC / PROVIDER_OPENAI */
    var provider: String
        get() = sp.getString("provider", PROVIDER_ANTHROPIC) ?: PROVIDER_ANTHROPIC
        set(value) = sp.edit().putString("provider", value).apply()

    // ---- Anthropic ----
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString("model", value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    // ---- OpenAI / compatible endpoint ----
    var openaiKey: String
        get() = sp.getString("openai_key", "") ?: ""
        set(value) = sp.edit().putString("openai_key", value.trim()).apply()

    var openaiModel: String
        get() = sp.getString("openai_model", OpenAiOracle.DEFAULT_MODEL) ?: OpenAiOracle.DEFAULT_MODEL
        set(value) = sp.edit()
            .putString("openai_model", value.trim().ifEmpty { OpenAiOracle.DEFAULT_MODEL }).apply()

    var openaiBaseUrl: String
        get() = sp.getString("openai_base_url", OpenAiOracle.DEFAULT_BASE_URL)
            ?: OpenAiOracle.DEFAULT_BASE_URL
        set(value) = sp.edit()
            .putString("openai_base_url", value.trim().ifEmpty { OpenAiOracle.DEFAULT_BASE_URL }).apply()

    /** Whether the currently selected backend has its API key configured. */
    val configured: Boolean
        get() = if (provider == PROVIDER_OPENAI) openaiKey.isNotEmpty() else apiKey.isNotEmpty()

    companion object {
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
        const val DEFAULT_MODEL = "claude-opus-4-8"
    }
}
