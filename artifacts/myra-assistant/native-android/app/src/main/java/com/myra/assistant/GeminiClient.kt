package com.myra.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AssistantAnswer(val text: String, val provider: String)

private enum class ProviderFailureKind { QUOTA, UNAVAILABLE, AUTH, OTHER }

private class ProviderFailure(
    val kind: ProviderFailureKind,
    val publicMessage: String,
) : Exception(publicMessage)

object GeminiClient {
    private const val GEMINI_MODEL = "gemini-2.5-flash"
    private const val OPENAI_MODEL = "gpt-5-mini"

    suspend fun respond(context: Context, prompt: String): AssistantAnswer =
        withContext(Dispatchers.IO) {
            val geminiKey = ApiKeyVault.getGeminiKey(context)
            if (geminiKey.isBlank()) {
                throw IllegalStateException("Add your Gemini API key in MYRA settings.")
            }

            try {
                AssistantAnswer(callGemini(geminiKey, prompt), "Gemini")
            } catch (geminiFailure: ProviderFailure) {
                val openAiKey = ApiKeyVault.getOpenAiKey(context)
                val canFailOver = geminiFailure.kind == ProviderFailureKind.QUOTA ||
                    geminiFailure.kind == ProviderFailureKind.UNAVAILABLE

                if (!canFailOver) throw IllegalStateException(geminiFailure.publicMessage)
                if (openAiKey.isBlank()) {
                    val message = if (geminiFailure.kind == ProviderFailureKind.QUOTA) {
                        "Gemini quota is exhausted. No OpenAI fallback key is configured, so no reply was generated."
                    } else {
                        "Gemini is unavailable. No OpenAI fallback key is configured, so no reply was generated."
                    }
                    throw IllegalStateException(message)
                }

                try {
                    AssistantAnswer(callOpenAi(openAiKey, prompt), "OpenAI fallback")
                } catch (fallbackFailure: ProviderFailure) {
                    throw IllegalStateException(
                        "Gemini failed and the configured OpenAI fallback also failed. " +
                            "No fallback reply was generated. ${fallbackFailure.publicMessage}",
                    )
                } catch (_: IOException) {
                    throw IllegalStateException(
                        "Gemini failed and the configured OpenAI fallback could not be reached. " +
                            "No fallback reply was generated.",
                    )
                }
            } catch (io: IOException) {
                val openAiKey = ApiKeyVault.getOpenAiKey(context)
                if (openAiKey.isBlank()) {
                    throw IllegalStateException(
                        "Gemini could not be reached. No OpenAI fallback key is configured, so no reply was generated.",
                    )
                }
                try {
                    AssistantAnswer(callOpenAi(openAiKey, prompt), "OpenAI fallback")
                } catch (fallbackFailure: ProviderFailure) {
                    throw IllegalStateException(
                        "Gemini could not be reached and the configured OpenAI fallback failed. " +
                            "No fallback reply was generated. ${fallbackFailure.publicMessage}",
                    )
                } catch (_: IOException) {
                    throw IllegalStateException(
                        "Gemini could not be reached and the configured OpenAI fallback could not be reached. " +
                            "No fallback reply was generated.",
                    )
                }
            }
        }

    private fun callGemini(apiKey: String, prompt: String): String {
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are MYRA, a concise personal assistant. Reply naturally in one or two spoken sentences. " +
                                "Do not claim to have performed phone actions; the app handles those separately.",
                        ),
                    ),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", 8192))
            .toString()

        val response = postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            body,
            mapOf("x-goog-api-key" to apiKey),
        )

        val candidates = response.optJSONArray("candidates")
        val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw ProviderFailure(ProviderFailureKind.OTHER, "Gemini returned no response text.")
        val answer = (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (answer.isBlank()) {
            throw ProviderFailure(ProviderFailureKind.OTHER, "Gemini returned an empty response.")
        }
        answer
    }

    private fun callOpenAi(apiKey: String, prompt: String): String {
        val body = JSONObject()
            .put("model", OPENAI_MODEL)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "role",
                            "system",
                        ).put(
                            "content",
                            "You are MYRA, a concise personal assistant. Reply naturally in one or two spoken sentences. " +
                                "Do not claim to have performed phone actions; the app handles those separately.",
                        ),
                    )
                    .put(JSONObject().put("role", "user").put("content", prompt)),
            )
            .put("max_completion_tokens", 8192)
            .toString()

        val response = postJson(
            "https://api.openai.com/v1/chat/completions",
            body,
            mapOf("Authorization" to "Bearer $apiKey"),
        )
        val answer = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (answer.isBlank()) {
            throw ProviderFailure(ProviderFailureKind.OTHER, "OpenAI returned no response text.")
        }
        answer
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw providerFailure(status, responseText)
            return JSONObject(responseText)
        } catch (failure: ProviderFailure) {
            throw failure
        } catch (io: IOException) {
            throw io
        } finally {
            connection.disconnect()
        }
    }

    private fun providerFailure(status: Int, body: String): ProviderFailure {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val lowerDetail = detail.lowercase()
        return when {
            status == 429 || lowerDetail.contains("quota") ||
                lowerDetail.contains("resource_exhausted") ->
                ProviderFailure(ProviderFailureKind.QUOTA, "Provider quota limit reached.")
            status == 401 || status == 403 ->
                ProviderFailure(ProviderFailureKind.AUTH, "The provider rejected its API key. Check the key in MYRA settings.")
            status >= 500 ->
                ProviderFailure(ProviderFailureKind.UNAVAILABLE, "The provider is temporarily unavailable.")
            else ->
                ProviderFailure(ProviderFailureKind.OTHER, "The provider request failed (HTTP $status).")
        }
    }
}
