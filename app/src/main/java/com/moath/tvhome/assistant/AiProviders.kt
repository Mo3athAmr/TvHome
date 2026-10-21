package com.moath.tvhome.assistant

import android.util.Base64
import android.util.Log
import com.moath.tvhome.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A function the model may call. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class ToolCall(val id: String, val name: String, val args: JSONObject)

/**
 * One turn of the conversation in a provider-neutral form. [raw] keeps a provider's own message
 * (Gemini's parts carry thought signatures that must be sent back unchanged).
 */
data class ChatMsg(
    val role: String, // "user" | "assistant" | "tool"
    val text: String? = null,
    val image: ByteArray? = null, // JPEG
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val raw: Any? = null,
    val rawProvider: String? = null,
    /** The user's own request that opens a turn (not a tool result, screenshot or system nudge). */
    val turnStart: Boolean = false,
)

data class AiReply(val text: String, val toolCalls: List<ToolCall>, val raw: Any?, val provider: String, val model: String)

/** [retryAfterMs]: how long the provider asked us to wait (rate limits), when it said. */
class AiException(message: String, val retryAfterMs: Long? = null) : IOException(message)

/** Google puts "retryDelay": "33237s" in its 429 body; others send nothing usable. */
internal fun retryAfter(body: String): Long? =
    Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

interface AiProvider {
    val id: String
    val label: String
    val configured: Boolean
    val model: String
    suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): AiReply
    /** Speech → text; null when this provider can't take audio. */
    suspend fun transcribe(wav: ByteArray): String?
    /** A grounded web answer for live facts; null when unsupported. */
    suspend fun webSearch(question: String): String?
}

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // A stuck provider must not hold the TV for long: the next one gets the question.
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    suspend fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("HTTP ${res.code}: ${text.take(300)}", if (res.code == 429) retryAfter(text) else null)
            JSONObject(text)
        }
    }

    suspend fun postForm(url: String, body: okhttp3.RequestBody, headers: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("HTTP ${res.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("HTTP ${res.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }
}

internal fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

/**
 * Text-only rendering of a history entry, for a provider that did not produce it. Tool calls are left
 * out on purpose: written as text ("[called tool …]") the next model copies that form instead of
 * really calling the tool, and nothing happens on the TV.
 */
internal fun ChatMsg.asPlainText(): String = when (role) {
    "tool" -> "(معلومة من النظام: ${text.orEmpty()})"
    "assistant" -> text.orEmpty().ifBlank { if (toolCalls.isNotEmpty()) "(تم تنفيذ الطلب)" else "" }
    else -> text.orEmpty()
}

// ── Gemini ──────────────────────────────────────────────────────────────────────────────────

class GeminiProvider(
    override val model: String,
    private val key: String = BuildConfig.GEMINI_API_KEY,
    /** Speech goes to a model measured fast and accurate on Gulf Arabic, whatever chat model is picked. */
    private val audioModel: String = "gemini-2.5-flash",
) : AiProvider {
    override val id = "gemini"
    override val label = "Gemini"
    override val configured get() = key.isNotBlank() && model.isNotBlank()
    private val base = "https://generativelanguage.googleapis.com/v1beta/models"
    private fun headers() = mapOf("x-goog-api-key" to key)

    override suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): AiReply {
        val contents = JSONArray()
        val ownCalls = messages.filter { it.rawProvider == id && it.raw != null }.flatMap { it.toolCalls }.map { it.id }.toSet()
        for (m in messages) {
            when {
                m.rawProvider == id && m.raw is JSONObject -> contents.put(m.raw)
                m.role == "tool" && m.toolCallId in ownCalls -> contents.put(
                    JSONObject().put("role", "user").put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", m.toolName).put("response", JSONObject().put("result", m.text.orEmpty()))
                                    .apply { if (m.toolCallId?.startsWith("local-") == false) put("id", m.toolCallId) },
                            ),
                        ),
                    ),
                )
                else -> {
                    val parts = JSONArray()
                    val t = if (m.role == "tool" || (m.rawProvider != null && m.rawProvider != id)) m.asPlainText() else m.text
                    if (!t.isNullOrBlank()) parts.put(JSONObject().put("text", t))
                    m.image?.let { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(it)))) }
                    if (parts.length() == 0) continue
                    contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user").put("parts", parts))
                }
            }
        }
        // Consecutive user turns (tool results + a screenshot) are sent as one.
        val merged = JSONArray()
        for (i in 0 until contents.length()) {
            val c = contents.getJSONObject(i)
            val last = if (merged.length() > 0) merged.getJSONObject(merged.length() - 1) else null
            if (last != null && last.optString("role") == "user" && c.optString("role") == "user") {
                val parts = c.getJSONArray("parts")
                for (j in 0 until parts.length()) last.getJSONArray("parts").put(parts.get(j))
            } else merged.put(if (c.optString("role") == "user") JSONObject(c.toString()) else c)
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", merged)
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 1024))
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach { decls.put(JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters)) }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        if (DEBUG) {
            body.toString().chunked(3500).forEachIndexed { i, c -> Log.d("AssistantReq", "$i $c") }
        }
        val res = Http.post("$base/$model:generateContent", body, headers())
        if (DEBUG) Log.d("AssistantRes", res.toString().take(3500))
        val content = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?: throw AiException("Gemini returned no candidate: ${res.toString().take(200)}")
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought")) continue
            p.optString("text").takeIf { it.isNotEmpty() }?.let(text::append)
            p.optJSONObject("functionCall")?.let { fc ->
                calls += ToolCall(fc.optString("id").ifEmpty { "local-${calls.size}" }, fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
            }
        }
        if (!content.has("role")) content.put("role", "model")
        return AiReply(text.toString().trim(), calls, content, id, model)
    }

    override suspend fun transcribe(wav: ByteArray): String? {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("text", TRANSCRIBE_PROMPT))
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64(wav)))),
                ),
            ),
        ).put("generationConfig", JSONObject().put("temperature", 0))
        val res = Http.post("$base/$audioModel:generateContent", body, headers())
        return res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.let { (0 until it.length()).joinToString("") { i -> it.getJSONObject(i).optString("text") } }?.trim()
    }

    override suspend fun webSearch(question: String): String? {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val res = Http.post("$base/$model:generateContent", body, headers())
        return res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.let { (0 until it.length()).joinToString("") { i -> it.getJSONObject(i).optString("text") } }?.trim()
    }
}

// ── Experiential Labs (OpenAI-compatible) ─────────────────────────────────────────────────

class OpenAiCompatProvider(
    override val model: String,
    private val audioModel: String,
    private val key: String = BuildConfig.EXPLABS_API_KEY,
    private val base: String = "https://api.experientiallabs.ai/v1",
) : AiProvider {
    override val id = "xpl"
    override val label = "Experiential Labs"
    override val configured get() = key.isNotBlank() && model.isNotBlank()
    private fun headers() = mapOf("Authorization" to "Bearer $key")

    private fun message(m: ChatMsg): JSONObject = when {
        m.role == "tool" -> JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.text.orEmpty())
        m.role == "assistant" && m.rawProvider == id && m.raw is JSONObject -> m.raw
        m.role == "assistant" -> JSONObject().put("role", "assistant").put("content", m.asPlainText())
        m.image != null -> JSONObject().put("role", "user").put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", m.text.orEmpty()))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64(m.image)))),
        )
        else -> JSONObject().put("role", "user").put("content", m.text.orEmpty())
    }

    override suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): AiReply {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        // A tool result must follow its own assistant call; drop orphaned ones from another provider.
        val ownCalls = messages.filter { it.rawProvider == id && it.raw != null }.flatMap { it.toolCalls }.map { it.id }.toSet()
        messages.forEach { m ->
            if (m.role == "tool" && m.toolCallId !in ownCalls) msgs.put(JSONObject().put("role", "user").put("content", m.asPlainText()))
            else msgs.put(message(m))
        }
        val body = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", 1024).put("temperature", 0.3)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach { arr.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters))) }
            body.put("tools", arr)
        }
        val res = Http.post("$base/chat/completions", body, headers())
        val msg = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw AiException("No choice: ${res.toString().take(200)}")
        val calls = ArrayList<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val f = c.optJSONObject("function") ?: continue
                val args = runCatching { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                calls += ToolCall(c.optString("id", "x$i"), f.getString("name"), args)
            }
        }
        val text = msg.optString("content").let { if (it == "null") "" else it }.trim()
        return AiReply(text, calls, msg, id, model)
    }

    /** Experiential serves speech-to-text on the OpenAI-style transcription endpoint (whisper-1, scribe_v2…). */
    override suspend fun transcribe(wav: ByteArray): String? {
        if (audioModel.isBlank()) return null
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", audioModel)
            .addFormDataPart("language", "ar")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return Http.postForm("$base/audio/transcriptions", form, headers()).optString("text").trim().takeIf { it.isNotEmpty() }
    }

    override suspend fun webSearch(question: String): String? {
        val body = JSONObject().put("model", "$model:online").put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
        val res = Http.post("$base/chat/completions", body, headers())
        return res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
    }
}

// ── Anthropic ───────────────────────────────────────────────────────────────────────────────

class AnthropicProvider(override val model: String, private val key: String = BuildConfig.ANTHROPIC_API_KEY) : AiProvider {
    override val id = "anthropic"
    override val label = "Anthropic"
    override val configured get() = key.isNotBlank() && model.isNotBlank()
    private fun headers() = anthropicHeaders(key)

    override suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): AiReply {
        val msgs = JSONArray()
        val ownCalls = messages.filter { it.rawProvider == id && it.raw != null }.flatMap { it.toolCalls }.map { it.id }.toSet()
        // Thinking blocks are only valid inside the turn that produced them (their signature binds them to it);
        // earlier turns go back without them, or the API rejects the whole request.
        val turn = messages.indexOfLast { it.turnStart }
        fun push(role: String, block: JSONObject) {
            // Anthropic wants alternating roles: merge consecutive blocks of the same role.
            val last = if (msgs.length() > 0) msgs.getJSONObject(msgs.length() - 1) else null
            if (last != null && last.getString("role") == role) last.getJSONArray("content").put(block)
            else msgs.put(JSONObject().put("role", role).put("content", JSONArray().put(block)))
        }
        for ((index, m) in messages.withIndex()) {
            when {
                m.role == "tool" && m.toolCallId in ownCalls ->
                    push("user", JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId).put("content", m.text.orEmpty()))
                m.role == "assistant" && m.rawProvider == id && m.raw is JSONArray -> {
                    val arr = m.raw
                    for (i in 0 until arr.length()) {
                        val b = arr.getJSONObject(i)
                        if (index < turn && b.optString("type").endsWith("thinking")) continue
                        push("assistant", b)
                    }
                }
                m.role == "assistant" -> m.asPlainText().takeIf { it.isNotBlank() }?.let { push("assistant", JSONObject().put("type", "text").put("text", it)) }
                else -> {
                    val t = if (m.role == "tool") m.asPlainText() else m.text
                    if (!t.isNullOrBlank()) push("user", JSONObject().put("type", "text").put("text", t))
                    m.image?.let {
                        push("user", JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(it))))
                    }
                }
            }
        }
        val body = JSONObject().put("model", model).put("max_tokens", 1024).put("system", system).put("messages", msgs)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach { arr.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.parameters)) }
            body.put("tools", arr)
        }
        val res = Http.post("https://api.anthropic.com/v1/messages", body, headers())
        val content = res.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls += ToolCall(b.getString("id"), b.getString("name"), b.optJSONObject("input") ?: JSONObject())
            }
        }
        return AiReply(text.toString().trim(), calls, content, id, model)
    }

    override suspend fun transcribe(wav: ByteArray): String? = null
    override suspend fun webSearch(question: String): String? = null
}

/** User keys (sk-ant-usr-…) that aren't tied to a workspace must name one on every request. */
internal fun anthropicHeaders(key: String): Map<String, String> = buildMap {
    put("x-api-key", key)
    put("anthropic-version", "2023-06-01")
    BuildConfig.ANTHROPIC_WORKSPACE_ID.takeIf { it.isNotBlank() }?.let { put("anthropic-workspace-id", it) }
}

internal const val TRANSCRIBE_PROMPT =
    "حوّل هذا التسجيل الصوتي إلى نص كما قيل حرفياً (غالباً باللهجة الخليجية أو العربية الفصحى، وقد يحتوي كلمات إنجليزية). اكتب النص فقط بلا أي تعليق. إذا لم يكن فيه كلام واضح فاكتب: (لا شيء)"

internal const val DEBUG = false

internal fun logW(msg: String, t: Throwable? = null) = Log.w("Assistant", msg, t)
