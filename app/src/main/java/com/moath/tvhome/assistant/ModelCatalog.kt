package com.moath.tvhome.assistant

import com.moath.tvhome.BuildConfig
import org.json.JSONObject

/** A model the owner can pick in Customize → Assistant. Prices are USD per 1M tokens (null = unknown). */
data class ModelOption(val id: String, val name: String, val inUsd: Double?, val outUsd: Double?, val audio: Boolean, val perMinuteUsd: Double? = null)

/**
 * Lists the models each provider offers. Experiential Labs is limited to models with tools whose
 * every active route costs at most [MAX_USD] per million tokens, input and output.
 */
object ModelCatalog {
    const val MAX_USD = 0.75
    private const val XPL_CATALOG = "https://api.experientiallabs.ai/api/models?limit=500&offset="

    suspend fun gemini(key: String = BuildConfig.GEMINI_API_KEY): List<ModelOption> {
        if (key.isBlank()) return emptyList()
        val res = Http.get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to key))
        val arr = res.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { o ->
                val methods = o.optJSONArray("supportedGenerationMethods")
                val id = o.optString("name").removePrefix("models/")
                id.startsWith("gemini") && !id.contains("tts") && !id.contains("image") && !id.contains("embedding") && !id.contains("live") &&
                    methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { o -> val id = o.optString("name").removePrefix("models/"); ModelOption(id, o.optString("displayName").ifBlank { id }, null, null, true) }
            .sortedWith(compareBy<ModelOption> { !it.id.contains("latest") }.thenByDescending { it.id })
    }

    /** (chat models with tools within the price cap, speech-to-text models for the transcription endpoint). */
    suspend fun experiential(key: String = BuildConfig.EXPLABS_API_KEY): Pair<List<ModelOption>, List<ModelOption>> {
        val all = ArrayList<JSONObject>()
        var offset = 0
        while (offset < 5000) {
            val page = Http.get(XPL_CATALOG + offset).optJSONArray("models") ?: break
            for (i in 0 until page.length()) all += page.getJSONObject(i)
            if (page.length() < 500) break
            offset += 500
        }
        // What this key may call (with its own prices); without a key, trust the public catalog.
        val granted: List<JSONObject> = if (key.isBlank()) emptyList() else runCatching {
            val d = Http.get("https://api.experientiallabs.ai/v1/models", mapOf("Authorization" to "Bearer $key")).optJSONArray("data")
            d?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        }.getOrDefault(emptyList())
        val allowed = granted.map { it.optString("id") }.toSet().takeIf { it.isNotEmpty() }
        val chat = all.mapNotNull { parse(it) }.filter { it.second && (allowed == null || it.first.id in allowed) }.map { it.first }
        return chat to granted.mapNotNull(::speech).sortedBy { it.perMinuteUsd }
    }

    /**
     * A speech-to-text model from the key's model list, or null. Chat models (Gemini included) are refused
     * audio by Experiential's chat route, so only the transcription models qualify: billed per audio second,
     * or per token within the price cap.
     */
    internal fun speech(m: JSONObject): ModelOption? {
        val id = m.optString("id")
        if (!(id.contains("whisper") || id.contains("transcribe") || id.startsWith("scribe")) || id.contains("diarize")) return null
        val pricing = m.optJSONObject("pricing") ?: return null
        val units = pricing.optJSONObject("units")
        if (units?.optString("kind") == "audio_second") {
            val rate = units.optJSONObject("rates")?.let { r -> r.keys().asSequence().map { r.optLong(it) }.maxOrNull() } ?: return null
            return ModelOption(id, id, null, null, true, perMinuteUsd = rate * 60 / 1e9)
        }
        val inUsd = pricing.optLong("input_nano_usd_per_million_tokens", 0) / 1e9
        val outUsd = pricing.optLong("output_nano_usd_per_million_tokens", 0) / 1e9
        if (inUsd <= 0 || inUsd > MAX_USD || outUsd > MAX_USD) return null
        return ModelOption(id, id, inUsd, outUsd, true)
    }

    /** One catalog entry → (option, supports tools), or null when it is over the cap or unusable. */
    internal fun parse(entry: JSONObject): Pair<ModelOption, Boolean>? {
        val m = entry.optJSONObject("model") ?: return null
        val slug = m.optString("slug")
        if (m.optString("status") != "active" || slug.endsWith("-batch") || slug.contains("uncensored")) return null
        val providers = entry.optJSONArray("providers") ?: return null
        var inMax = 0L
        var outMax = 0L
        var any = false
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            if (p.optString("status") != "active") continue
            val inp = p.optLong("input_nano_usd_per_million", 0)
            val out = p.optLong("output_nano_usd_per_million", 0)
            if (inp <= 0 || out <= 0) continue // unpriced routes can't be checked against the cap
            any = true
            inMax = maxOf(inMax, inp)
            outMax = maxOf(outMax, out)
        }
        if (!any) return null
        val inUsd = inMax / 1e9
        val outUsd = outMax / 1e9
        if (inUsd > MAX_USD || outUsd > MAX_USD) return null
        val mods = m.optJSONArray("input_modalities")
        val audio = mods != null && (0 until mods.length()).any { mods.getString(it) == "audio" }
        val tools = m.optJSONObject("supported_params")?.optBoolean("tools") == true
        if (!tools && !audio) return null
        return ModelOption(slug, m.optString("display_name").ifBlank { slug }, inUsd, outUsd, audio) to tools
    }

    suspend fun anthropic(key: String = BuildConfig.ANTHROPIC_API_KEY): List<ModelOption> {
        if (key.isBlank()) return emptyList()
        val res = Http.get("https://api.anthropic.com/v1/models?limit=100", anthropicHeaders(key))
        val arr = res.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { ModelOption(it.optString("id"), it.optString("display_name").ifBlank { it.optString("id") }, null, null, false) }
    }
}
