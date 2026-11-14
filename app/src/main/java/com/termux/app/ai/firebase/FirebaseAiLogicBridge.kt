package com.termux.app.ai.firebase

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.termux.app.ai.model.AIError
import com.termux.app.ai.model.AIException
import com.termux.app.ai.model.AIMessage
import com.termux.app.ai.model.AIModelInfo
import com.termux.app.ai.model.AIProviderType
import com.termux.app.ai.model.AIRequest
import com.termux.app.ai.model.AIResponse
import com.termux.app.ai.model.AIStreamCallback
import com.termux.app.ai.model.AIUsage
import com.termux.app.ai.model.CancellationSignalHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Bridge connecting Java AIManager/BuiltInFirebaseProvider with the Firebase AI Logic Kotlin SDK.
 * Uses zero-config App Check attestation (no client API key required).
 */
object FirebaseAiLogicBridge {

    private const val TAG = "FirebaseAiLogicBridge"
    private const val DEFAULT_MODEL_ID = "gemini-3.8-flash"
    private const val REQUEST_TIMEOUT_MS = 60_000L
    private const val TEST_TIMEOUT_MS = 30_000L

    private val defaultGenerativeModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(DEFAULT_MODEL_ID)
    }

    private fun createConfiguredModel(
        modelId: String,
        systemPrompt: String?,
        temperature: Float,
        maxOutputTokens: Int
    ): GenerativeModel {
        val targetModel = sanitizeSupportedModel(modelId)
        val hasCustomPrompt = !systemPrompt.isNullOrBlank()
        if (targetModel == DEFAULT_MODEL_ID && !hasCustomPrompt && temperature == 0.3f && maxOutputTokens == 2048) {
            return defaultGenerativeModel
        }
        val modelLazy by lazy {
            Firebase.ai.generativeModel(
                modelName = targetModel,
                generationConfig = generationConfig {
                    this.temperature = temperature.coerceIn(0.0f, 2.0f)
                    this.maxOutputTokens = maxOutputTokens.coerceIn(128, 32768)
                },
                systemInstruction = if (hasCustomPrompt) {
                    content { text(systemPrompt!!) }
                } else {
                    null
                }
            )
        }
        return modelLazy
    }

    @JvmStatic
    fun sanitizeSupportedModel(requestedModelId: String?): String {
        val trimmed = requestedModelId?.trim().orEmpty()
        return when (trimmed) {
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon" -> trimmed
            else -> DEFAULT_MODEL_ID
        }
    }

    @JvmStatic
    fun sendMessage(
        request: AIRequest,
        callback: AIStreamCallback,
        cancelHandle: CancellationSignalHandle?
    ) {
        if (cancelHandle?.isCancelled == true) {
            callback.onError(
                AIError(
                    AIError.Type.CANCELLED,
                    0,
                    "Generation stopped by user.",
                    AIProviderType.BUILTIN,
                    false
                )
            )
            return
        }

        val modelId = sanitizeSupportedModel(request.modelId)
        val rawMessages = request.messages
        val conversationTurns = mutableListOf<AIMessage>()
        for (msg in rawMessages) {
            if (msg.role == AIMessage.Role.USER || msg.role == AIMessage.Role.ASSISTANT) {
                if (msg.content.isNotBlank() && msg.errorMessage.isNullOrEmpty()) {
                    conversationTurns.add(msg)
                }
            }
        }

        if (conversationTurns.isEmpty()) {
            callback.onError(
                AIError(
                    AIError.Type.UNKNOWN,
                    0,
                    "Please enter a prompt.",
                    AIProviderType.BUILTIN,
                    false
                )
            )
            return
        }

        val lastTurn = conversationTurns.removeAt(conversationTurns.lastIndex)
        val history = mutableListOf<Content>()
        for (turn in conversationTurns) {
            val role = if (turn.role == AIMessage.Role.USER) "user" else "model"
            history.add(content(role) { text(turn.content) })
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            val accumulated = StringBuilder()
            var lastUsage: AIUsage? = null
            var lastFinishReason: String? = null
            try {
                val model = createConfiguredModel(
                    modelId = modelId,
                    systemPrompt = request.systemPrompt,
                    temperature = request.temperature,
                    maxOutputTokens = request.maxOutputTokens
                )
                val chat = model.startChat(history = history)

                if (request.isStream) {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        chat.sendMessageStream(lastTurn.content).collect { chunk ->
                            if (cancelHandle?.isCancelled == true) {
                                throw CancellationException("Cancelled by user")
                            }
                            val chunkText = chunk.text.orEmpty()
                            if (chunkText.isNotEmpty()) {
                                accumulated.append(chunkText)
                                callback.onChunk(chunkText, accumulated.toString())
                            }
                            chunk.usageMetadata?.let { meta ->
                                lastUsage = AIUsage(
                                    meta.promptTokenCount ?: 0,
                                    meta.candidatesTokenCount ?: 0,
                                    meta.totalTokenCount ?: 0
                                )
                            }
                            chunk.candidates.firstOrNull()?.finishReason?.let { reason ->
                                lastFinishReason = reason.name
                            }
                        }
                    }
                    if (accumulated.isBlank()) {
                        callback.onError(
                            AIError(
                                AIError.Type.MALFORMED_RESPONSE,
                                0,
                                "Built-in AI returned an empty response.",
                                AIProviderType.BUILTIN,
                                false
                            )
                        )
                    } else {
                        callback.onComplete(
                            AIResponse(
                                accumulated.toString(),
                                lastUsage,
                                AIProviderType.BUILTIN,
                                modelId,
                                lastFinishReason
                            )
                        )
                    }
                } else {
                    val response = withTimeout(REQUEST_TIMEOUT_MS) {
                        chat.sendMessage(lastTurn.content)
                    }
                    val text = response.text.orEmpty()
                    if (text.isBlank()) {
                        callback.onError(
                            AIError(
                                AIError.Type.MALFORMED_RESPONSE,
                                0,
                                "Built-in AI returned an empty response.",
                                AIProviderType.BUILTIN,
                                false
                            )
                        )
                    } else {
                        val meta = response.usageMetadata
                        val usage = if (meta != null) {
                            AIUsage(
                                meta.promptTokenCount ?: 0,
                                meta.candidatesTokenCount ?: 0,
                                meta.totalTokenCount ?: 0
                            )
                        } else null
                        val finishReason = response.candidates.firstOrNull()?.finishReason?.name
                        callback.onComplete(
                            AIResponse(
                                text,
                                usage,
                                AIProviderType.BUILTIN,
                                modelId,
                                finishReason
                            )
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Built-in AI generation timed out", e)
                callback.onError(
                    AIError(
                        AIError.Type.TIMEOUT,
                        0,
                        "Built-in AI timed out after 60 seconds. Please check your connection and try again.",
                        AIProviderType.BUILTIN,
                        true
                    )
                )
            } catch (e: CancellationException) {
                callback.onError(
                    AIError(
                        AIError.Type.CANCELLED,
                        0,
                        "Generation stopped by user.",
                        AIProviderType.BUILTIN,
                        false
                    )
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Built-in AI generation failed", e)
                callback.onError(mapExceptionToError(e))
            }
        }

        cancelHandle?.setOnCancelHook {
            job.cancel()
        }

        runBlocking {
            job.join()
        }
    }

    @JvmStatic
    @Throws(AIException::class)
    fun testConnection(modelId: String?): AIModelInfo {
        val targetModel = sanitizeSupportedModel(modelId)
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(TEST_TIMEOUT_MS) {
                    val model = createConfiguredModel(
                        modelId = targetModel,
                        systemPrompt = null,
                        temperature = 0.0f,
                        maxOutputTokens = 128
                    )
                    val response = model.generateContent("Reply with OK.")
                    if (response.text.isNullOrBlank()) {
                        throw AIException(
                            AIError(
                                AIError.Type.MALFORMED_RESPONSE,
                                0,
                                "Built-in AI returned an empty test response.",
                                AIProviderType.BUILTIN,
                                false
                            )
                        )
                    }
                    AIModelInfo(
                        targetModel,
                        displayNameFor(targetModel),
                        AIProviderType.BUILTIN,
                        true,
                        1048576
                    )
                }
            }
        } catch (e: AIException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Built-in AI connection test timed out", e)
            throw AIException(
                AIError(
                    AIError.Type.TIMEOUT,
                    0,
                    "Built-in AI connection test timed out. Check network connectivity.",
                    AIProviderType.BUILTIN,
                    true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Built-in AI connection test failed", e)
            throw AIException(mapExceptionToError(e))
        }
    }

    @JvmStatic
    fun listBuiltInModels(): List<AIModelInfo> {
        return listOf(
            AIModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash (Default)", AIProviderType.BUILTIN, true, 1048576),
            AIModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (Fast)", AIProviderType.BUILTIN, true, 1048576),
            AIModelInfo("gemini-4-argon", "Gemini 4 Argon (Advanced / Blaze)", AIProviderType.BUILTIN, false, 1048576)
        )
    }

    private fun displayNameFor(modelId: String): String {
        return when (modelId) {
            "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash-Lite"
            "gemini-4-argon" -> "Gemini 4 Argon"
            else -> "Gemini 3.8 Flash"
        }
    }

    private fun mapExceptionToError(e: Exception): AIError {
        val raw = e.localizedMessage ?: e.message ?: "Built-in AI request failed."
        val lower = raw.lowercase()
        val type = when {
            e is IOException || lower.contains("unable to resolve host") || lower.contains("network") -> AIError.Type.NO_INTERNET
            lower.contains("403") || lower.contains("app check") || lower.contains("attestation") -> AIError.Type.UNAUTHORIZED
            lower.contains("429") || lower.contains("rate") -> AIError.Type.RATE_LIMITED
            lower.contains("quota") || lower.contains("billing") -> AIError.Type.QUOTA_EXCEEDED
            lower.contains("timeout") -> AIError.Type.TIMEOUT
            lower.contains("model") && lower.contains("not found") -> AIError.Type.INVALID_MODEL
            else -> AIError.Type.SERVER_ERROR
        }
        val retryable = type == AIError.Type.RATE_LIMITED || type == AIError.Type.TIMEOUT || type == AIError.Type.NO_INTERNET
        return AIError(type, 0, raw, AIProviderType.BUILTIN, retryable)
    }
}
