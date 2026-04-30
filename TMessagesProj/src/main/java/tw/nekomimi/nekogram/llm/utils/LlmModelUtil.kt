package tw.nekomimi.nekogram.llm.utils

import org.json.JSONObject
import tw.nekomimi.nekogram.llm.preset.LlmPresetRegistry

object LlmModelUtil {

    @JvmStatic
    fun getBaseModelName(model: String?): String {
        if (model.isNullOrBlank()) {
            return ""
        }
        return model.trim().substringAfterLast('/')
    }

    @JvmStatic
    fun isGPT5(model: String?): Boolean {
        val base = getBaseModelName(model).lowercase()
        return !base.startsWith("gpt-5.") && base.startsWith("gpt-5") && !base.contains("instant") && !base.contains("chat")
    }

    @JvmStatic
    fun isCerebrasGlm(url: String?, model: String?): Boolean {
        val base = getBaseModelName(model).lowercase()
        return url == LlmPresetRegistry.getPresetBaseUrl(LlmPresetRegistry.CEREBRAS) && base == "zai-glm-4.7"
    }

    @JvmStatic
    fun isDeepSeekV4(model: String?): Boolean {
        val base = getBaseModelName(model).lowercase()
        return base.startsWith("deepseek-v4")
    }

    @JvmStatic
    fun isReasoning(model: String?): Boolean {
        val base = getBaseModelName(model).lowercase()
        return base.contains("gemini") && base.contains("flash")
                || base.startsWith("gpt-oss")
                || (base.startsWith("gpt-5") && !base.contains("instant") && !base.contains("chat"))
    }

    @JvmStatic
    fun getReasoningEffort(model: String?): String {
        val base = getBaseModelName(model).lowercase()
        return when {
            base.startsWith("gpt-oss") -> "low"
            base.startsWith("gpt-5.") -> "none"
            base.startsWith("gpt-5") -> "minimal"
            else -> "none"
        }
    }

    @JvmStatic
    fun applyReasoningParameters(requestJson: JSONObject, url: String?, model: String?) {
        if (isReasoning(model)) {
            requestJson.put("reasoning_effort", getReasoningEffort(model))
        } else if (isCerebrasGlm(url, model)) {
            requestJson.put("disable_reasoning", true)
        } else if (isDeepSeekV4(model)) {
            if (url == LlmPresetRegistry.getPresetBaseUrl(LlmPresetRegistry.VERCEL_AI_GATEWAY)) {
                requestJson.put(
                    "providerOptions",
                    JSONObject().put(
                        "deepseek",
                        JSONObject().put("thinking", JSONObject().put("type", "disabled"))
                    )
                )
            } else {
                requestJson.put("thinking", JSONObject().put("type", "disabled"))
            }
        }
    }

    @JvmStatic
    fun supportsTemperature(model: String?): Boolean {
        val base = getBaseModelName(model).lowercase()
        return !base.startsWith("gpt-5")
    }

    @JvmStatic
    fun stripModelsPrefix(models: List<String?>?): List<String> {
        if (models.isNullOrEmpty()) {
            return emptyList()
        }
        val out = LinkedHashSet<String>()
        for (model in models) {
            if (model == null) {
                continue
            }
            var id = model.trim()
            if (id.startsWith("models/")) {
                id = id.substring("models/".length)
            }
            if (id.isNotEmpty()) {
                out.add(id)
            }
        }
        return out.toList()
    }

    @JvmStatic
    fun isOpenRouterFreeModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) {
            return false
        }
        return modelId.trim().endsWith(":free", ignoreCase = true)
    }
}
