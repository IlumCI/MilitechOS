package eu.euroswarms.surgeon.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class OllamaMessage(val role: String = "", val content: String = "")

@Serializable
private data class OllamaChatResponse(
    val message: OllamaMessage = OllamaMessage(),
    @SerialName("done") val done: Boolean = true,
)

data class ChatMessage(val role: String, val content: String)

/**
 * Talks to an Ollama-compatible /api/chat endpoint. Works against a self-hosted server
 * (http://host:11434) or Ollama Cloud (https://ollama.com with an API key).
 */
class OllamaClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
) {
    private val jsonMedia = "application/json".toMediaType()
    private val endpoint = baseUrl.trimEnd('/') + "/api/chat"

    /**
     * Sends a non-streaming chat completion. When [forceJson] is set, asks the server to
     * constrain output to a JSON object (Ollama's `format: "json"`).
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.2,
        forceJson: Boolean = true,
    ): String {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            if (forceJson) put("format", "json")
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
            put("options", buildJsonObject {
                put("temperature", temperature)
            })
        }

        val builder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Surgeon-Android")
            .post(payload.toString().toRequestBody(jsonMedia))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val body = withContext(Dispatchers.IO) {
            Http.client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, text, "Ollama chat → ${resp.code}")
                }
                text
            }
        }
        return Http.json.decodeFromString(OllamaChatResponse.serializer(), body).message.content
    }
}
