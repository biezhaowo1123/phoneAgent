package com.phoneagent.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * AI Service: universal LLM client supporting all major providers.
 * Supports non-streaming, streaming (SSE), vision, and image generation.
 */
class AiService(private var config: AiConfig) {

    val currentModel: String get() = config.model
    val currentConfig: AiConfig get() = config
    @Volatile private var strictToolNameResolution: Boolean = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    /** Send a JsonObject as request body. */
    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: JsonObject) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    fun updateConfig(newConfig: AiConfig) {
        config = newConfig
    }

    /** Strict mode disables fuzzy tool name matching to avoid accidental mis-invocations. */
    fun setStrictToolNameResolution(enabled: Boolean) {
        strictToolNameResolution = enabled
    }

    /** Non-streaming chat (used in agent tool loop). */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        mustKeepTools: Boolean = false,
    ): ChatMessage = withContext(Dispatchers.IO) {
        if (config.apiKey.isEmpty()) {
            return@withContext ChatMessage(role = "assistant", content = "请先在设置中配置 API Key")
        }
        try {
            when (config.provider) {
                AiProvider.CLAUDE -> chatClaude(messages, tools)
                AiProvider.GEMINI -> chatGemini(messages, tools)
                else -> chatOpenAI(messages, tools, mustKeepTools)
            }
        } catch (e: Exception) {
            ChatMessage(role = "assistant", content = "AI请求失败: ${e.message}")
        }
    }

    /** Streaming chat — emits TextDelta events, then a Complete event. */
    fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        mustKeepTools: Boolean = false,
    ): Flow<StreamEvent> {
        return when (config.provider) {
            AiProvider.CLAUDE -> streamClaude(messages, tools)
            AiProvider.GEMINI -> flow {
                // Gemini streamGenerateContent has a different format; fall back for now
                val result = chatGemini(messages, tools)
                emit(StreamEvent.TextDelta(result.content))
                emit(StreamEvent.Complete(result))
            }
            else -> streamOpenAI(messages, tools, mustKeepTools)
        }
    }

    /** Generate an image via DALL-E compatible API. Returns image URL. */
    suspend fun generateImage(
        prompt: String,
        size: String = "1024x1024",
        quality: String = "standard",
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", "dall-e-3")
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("quality", quality)
        }

        val endpoint = "${config.baseUrl}/images/generations"
        val response = client.post(endpoint) {
            header("Authorization", "Bearer ${config.apiKey}")
            jsonBody(body)
        }
        val responseJson = json.parseToJsonElement(response.body<String>()).jsonObject
        responseJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.content ?: "生成失败"
    }

    // ========================================================================
    //  OpenAI-compatible (non-streaming)
    // ========================================================================

    private suspend fun chatOpenAI(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        mustKeepTools: Boolean,
    ): ChatMessage {
        val includeSampling = config.provider != AiProvider.MINIMAX
        val includeToolChoice = config.provider != AiProvider.MINIMAX
        val body = buildOpenAIBody(
            messages = messages,
            tools = tools,
            stream = false,
            maxContextMessages = 18,
            maxContextChars = 12_000,
            includeSampling = includeSampling,
            includeToolChoice = includeToolChoice,
        )
        val preset = AiConfig.PRESETS[config.provider]
        val authType = preset?.authType ?: "Bearer"
        val url = "${config.baseUrl}/chat/completions"

        suspend fun postBody(reqBody: JsonObject): Pair<HttpResponse, String> {
            val response = client.post(url) {
                if (authType == "Bearer") {
                    header("Authorization", "Bearer ${config.apiKey}")
                } else {
                    header(authType, config.apiKey)
                }
                if (config.provider == AiProvider.OPENROUTER) {
                    header("HTTP-Referer", "https://phoneagent.app")
                    header("X-Title", "PhoneAgent")
                }
                jsonBody(reqBody)
            }
            val responseText = try { response.body<String>() } catch (_: Exception) { "" }
            return response to responseText
        }

        var (response, responseText) = postBody(body)
        if (shouldRetryWithRelaxedChatSettings(response.status.value, responseText)) {
            val retryPlans = buildOpenAIRetryPlans(
                messages = messages,
                tools = tools,
                stream = false,
                allowDisableTools = !mustKeepTools,
            )
            for ((index, plan) in retryPlans.withIndex()) {
                android.util.Log.w(
                    "AiService",
                    "chatOpenAI: invalid chat setting, retry ${index + 1}/${retryPlans.size} -> ${plan.first}"
                )
                val retried = postBody(plan.second)
                response = retried.first
                responseText = retried.second
                if (response.status.value in 200..299) break
                if (!shouldRetryWithRelaxedChatSettings(response.status.value, responseText)) break
            }
        }

        if (response.status.value !in 200..299) {
            return ChatMessage(
                role = "assistant",
                content = "API返回错误(${response.status.value}): $responseText"
            )
        }

        val responseJson = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            return ChatMessage(role = "assistant", content = "解析响应失败: ${e.message}")
        }
        val choice = responseJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull ?: ""

        val toolCalls = message?.get("tool_calls")?.jsonArray?.map { tc ->
            val fn = tc.jsonObject["function"]?.jsonObject
            ToolCall(
                name = fn?.get("name")?.jsonPrimitive?.content ?: "",
                arguments = fn?.get("arguments")?.let { args ->
                    try {
                        json.parseToJsonElement(args.jsonPrimitive.content).jsonObject
                            .mapValues { it.value.jsonPrimitive.content }
                    } catch (_: Exception) { emptyMap() }
                } ?: emptyMap()
            )
        }

        // If no standard tool_calls, try parsing from text content
        val finalContent: String
        val finalToolCalls: List<ToolCall>?
        if (toolCalls.isNullOrEmpty()) {
            val (cleaned, parsed) = parseToolCallsFromText(content)
            finalContent = cleaned
            finalToolCalls = parsed.ifEmpty { null }
        } else {
            finalContent = content
            finalToolCalls = toolCalls.ifEmpty { null }
        }

        return ChatMessage(
            role = "assistant",
            content = finalContent,
            toolCalls = finalToolCalls
        )
    }

    // ========================================================================
    //  OpenAI-compatible (streaming SSE)
    // ========================================================================

    private fun streamOpenAI(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        mustKeepTools: Boolean,
    ): Flow<StreamEvent> = channelFlow {
        try {
            val includeSampling = config.provider != AiProvider.MINIMAX
            val includeToolChoice = config.provider != AiProvider.MINIMAX
            val body = buildOpenAIBody(
                messages = messages,
                tools = tools,
                stream = true,
                maxContextMessages = 18,
                maxContextChars = 12_000,
                includeSampling = includeSampling,
                includeToolChoice = includeToolChoice,
            )
            val preset = AiConfig.PRESETS[config.provider]
            val authType = preset?.authType ?: "Bearer"
            val url = "${config.baseUrl}/chat/completions"
            android.util.Log.d("AiService", "streamOpenAI: POST $url, bodyLen=${body.toString().length}")

            val retryPlans = buildOpenAIRetryPlans(
                messages = messages,
                tools = tools,
                stream = true,
                allowDisableTools = !mustKeepTools,
            )

            suspend fun runStreamRequest(
                requestBody: JsonObject,
                remainingRetryPlans: List<Pair<String, JsonObject>>,
            ) {
                client.preparePost(url) {
                    accept(ContentType.Text.EventStream)
                    if (authType == "Bearer") {
                        header("Authorization", "Bearer ${config.apiKey}")
                    } else {
                        header(authType, config.apiKey)
                    }
                    if (config.provider == AiProvider.OPENROUTER) {
                        header("HTTP-Referer", "https://phoneagent.app")
                        header("X-Title", "PhoneAgent")
                    }
                    jsonBody(requestBody)
                }.execute { httpResponse ->
                    android.util.Log.d("AiService", "streamOpenAI: status=${httpResponse.status}")
                    if (httpResponse.status.value !in 200..299) {
                        val errBody = try { httpResponse.body<String>() } catch (_: Exception) { "unknown" }
                        if (remainingRetryPlans.isNotEmpty() &&
                            shouldRetryWithRelaxedChatSettings(httpResponse.status.value, errBody)
                        ) {
                            val nextPlan = remainingRetryPlans.first()
                            android.util.Log.w(
                                "AiService",
                                "streamOpenAI: invalid chat setting, retry -> ${nextPlan.first}"
                            )
                            runStreamRequest(nextPlan.second, remainingRetryPlans.drop(1))
                            return@execute
                        }
                        android.util.Log.e("AiService", "streamOpenAI: error response: $errBody")
                        send(StreamEvent.TextDelta("API返回错误(${httpResponse.status.value}): $errBody"))
                        send(StreamEvent.Complete(ChatMessage(role = "assistant", content = "API返回错误(${httpResponse.status.value}): $errBody")))
                        return@execute
                    }

                    val fullContent = StringBuilder()
                    val toolCallAcc = mutableMapOf<Int, Pair<StringBuilder, StringBuilder>>()

                    val channel = httpResponse.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break

                        try {
                            val obj = json.parseToJsonElement(data).jsonObject
                            val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("delta")?.jsonObject ?: continue

                            delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                                fullContent.append(text)
                                send(StreamEvent.TextDelta(text))
                            }

                            delta["tool_calls"]?.jsonArray?.forEach { tc ->
                                val tcObj = tc.jsonObject
                                val idx = tcObj["index"]?.jsonPrimitive?.int ?: 0
                                val fn = tcObj["function"]?.jsonObject
                                val pair = toolCallAcc.getOrPut(idx) { StringBuilder() to StringBuilder() }
                                fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { pair.first.append(it) }
                                fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { pair.second.append(it) }
                            }
                        } catch (_: Exception) { }
                    }

                    val toolCalls = if (toolCallAcc.isNotEmpty()) {
                        toolCallAcc.entries.sortedBy { it.key }.map { (_, pair) ->
                            ToolCall(
                                name = pair.first.toString(),
                                arguments = try {
                                    json.parseToJsonElement(pair.second.toString()).jsonObject
                                        .mapValues { it.value.jsonPrimitive.content }
                                } catch (_: Exception) { emptyMap() }
                            )
                        }
                    } else null

                    // If no standard tool_calls found, try parsing from text content
                    val finalContent: String
                    val finalToolCalls: List<ToolCall>?
                    if (toolCalls.isNullOrEmpty()) {
                        val (cleaned, parsed) = parseToolCallsFromText(fullContent.toString())
                        finalContent = cleaned
                        finalToolCalls = parsed.ifEmpty { null }
                    } else {
                        finalContent = fullContent.toString()
                        finalToolCalls = toolCalls
                    }

                    send(StreamEvent.Complete(ChatMessage(
                        role = "assistant",
                        content = finalContent,
                        toolCalls = finalToolCalls
                    )))
                }
            }
            runStreamRequest(body, retryPlans)
        } catch (e: Exception) {
            android.util.Log.e("AiService", "streamOpenAI exception", e)
            val errorMsg = when {
                config.apiKey.isEmpty() -> "请先在设置中配置 API Key"
                e.message?.contains("401") == true -> "API Key 无效，请检查设置"
                e.message?.contains("429") == true -> "请求过于频繁，请稍后再试"
                e.message?.contains("timeout") == true -> "请求超时，请检查网络"
                else -> "请求失败: ${e.message}"
            }
            send(StreamEvent.TextDelta(errorMsg))
            send(StreamEvent.Complete(ChatMessage(role = "assistant", content = errorMsg)))
        }
    }

    // ========================================================================
    //  Claude (Anthropic) — non-streaming
    // ========================================================================

    private suspend fun chatClaude(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage {
        val body = buildClaudeBody(messages, tools, stream = false)

        val response = client.post("${config.baseUrl}/messages") {
            header("x-api-key", config.apiKey)
            header("anthropic-version", "2023-06-01")
            jsonBody(body)
        }

        val responseJson = json.parseToJsonElement(response.body<String>()).jsonObject
        return parseClaudeResponse(responseJson)
    }

    // ========================================================================
    //  Claude (Anthropic) — streaming SSE
    // ========================================================================

    private fun streamClaude(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): Flow<StreamEvent> = channelFlow {
        try {
            val body = buildClaudeBody(messages, tools, stream = true)

            client.preparePost("${config.baseUrl}/messages") {
                accept(ContentType.Text.EventStream)
                header("x-api-key", config.apiKey)
                header("anthropic-version", "2023-06-01")
                jsonBody(body)
            }.execute { httpResponse ->
            val fullContent = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            var currentToolName = ""
            val currentToolArgs = StringBuilder()

            val channel = httpResponse.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()

                try {
                    val obj = json.parseToJsonElement(data).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "content_block_start" -> {
                            val block = obj["content_block"]?.jsonObject
                            if (block?.get("type")?.jsonPrimitive?.content == "tool_use") {
                                currentToolName = block["name"]?.jsonPrimitive?.content ?: ""
                                currentToolArgs.clear()
                            }
                        }
                        "content_block_delta" -> {
                            val delta = obj["delta"]?.jsonObject
                            when (delta?.get("type")?.jsonPrimitive?.content) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                    fullContent.append(text)
                                    send(StreamEvent.TextDelta(text))
                                }
                                "input_json_delta" -> {
                                    delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let {
                                        currentToolArgs.append(it)
                                    }
                                }
                            }
                        }
                        "content_block_stop" -> {
                            if (currentToolName.isNotEmpty()) {
                                toolCalls.add(ToolCall(
                                    name = currentToolName,
                                    arguments = try {
                                        json.parseToJsonElement(currentToolArgs.toString()).jsonObject
                                            .mapValues { it.value.jsonPrimitive.content }
                                    } catch (_: Exception) { emptyMap() }
                                ))
                                currentToolName = ""
                            }
                        }
                        "message_stop" -> break
                    }
                } catch (_: Exception) { }
            }

            send(StreamEvent.Complete(ChatMessage(
                role = "assistant",
                content = fullContent.toString(),
                toolCalls = toolCalls.ifEmpty { null }
            )))
        }
        } catch (e: Exception) {
            val errorMsg = when {
                config.apiKey.isEmpty() -> "请先在设置中配置 API Key"
                e.message?.contains("401") == true -> "API Key 无效，请检查设置"
                else -> "请求失败: ${e.message}"
            }
            send(StreamEvent.TextDelta(errorMsg))
            send(StreamEvent.Complete(ChatMessage(role = "assistant", content = errorMsg)))
        }
    }

    // ========================================================================
    //  Gemini (Google) — non-streaming
    // ========================================================================

    private suspend fun chatGemini(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", config.systemPrompt) } }
                }
                addJsonObject {
                    put("role", "model")
                    putJsonArray("parts") { addJsonObject { put("text", "好的，我已准备好。") } }
                }
                messages.filter { it.role != "tool" }.forEach { msg ->
                    addJsonObject {
                        put("role", if (msg.role == "user") "user" else "model")
                        putJsonArray("parts") {
                            addJsonObject { put("text", msg.content) }
                            msg.images?.forEach { img ->
                                addJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", "image/jpeg")
                                        put("data", img)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", config.temperature)
                put("maxOutputTokens", config.maxTokens)
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    putJsonObject("parameters") {
                                        put("type", "OBJECT")
                                        putJsonObject("properties") {
                                            tool.parameters.forEach { (name, param) ->
                                                putJsonObject(name) {
                                                    put("type", param.type.uppercase())
                                                    put("description", param.description)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val endpoint = "${config.baseUrl}/models/${config.model}:generateContent?key=${config.apiKey}"
        val response = client.post(endpoint) {
            jsonBody(body)
        }

        val responseJson = json.parseToJsonElement(response.body<String>()).jsonObject
        val parts = responseJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()
        parts?.forEach { part ->
            val obj = part.jsonObject
            obj["text"]?.jsonPrimitive?.contentOrNull?.let { textParts.add(it) }
            obj["functionCall"]?.jsonObject?.let { fc ->
                toolCalls.add(ToolCall(
                    name = fc["name"]?.jsonPrimitive?.content ?: "",
                    arguments = fc["args"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                ))
            }
        }

        return ChatMessage(
            role = "assistant",
            content = textParts.joinToString("\n"),
            toolCalls = toolCalls.ifEmpty { null }
        )
    }

    // ========================================================================
    //  Body builders
    // ========================================================================

    private fun buildOpenAIBody(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
        maxContextMessages: Int = 18,
        maxContextChars: Int = 12_000,
        includeSampling: Boolean = true,
        includeTools: Boolean = true,
        includeToolChoice: Boolean = true,
    ): JsonObject {
        // --- 1. Trim history to avoid context overflow (MiniMax 2013 error) ---
        val trimmed = if (messages.size > maxContextMessages) {
            messages.takeLast(maxContextMessages)
        } else {
            messages
        }

        // --- 2. Map 'tool' → 'user' and merge consecutive same-role messages ---
        data class Msg(val role: String, val content: String, val images: List<String>?)
        val processed = mutableListOf<Msg>()
        for (msg in trimmed) {
            // Tool results should appear as 'user' so the dialogue alternates user/assistant
            val role = if (msg.role == "tool") "user" else msg.role
            val imgs = if (msg.role == "user") msg.images else null
            if (processed.isNotEmpty() && processed.last().role == role
                && imgs.isNullOrEmpty() && processed.last().images.isNullOrEmpty()
            ) {
                val prev = processed.removeLast()
                processed.add(Msg(role, prev.content + "\n" + msg.content, null))
            } else {
                processed.add(Msg(role, msg.content, imgs))
            }
        }

        // --- 3. Enforce character budget from newest to oldest ---
        val budgetedReversed = mutableListOf<Msg>()
        var usedChars = 0
        for (msg in processed.asReversed()) {
            val clipped = if (msg.content.length > 2_500) msg.content.takeLast(2_500) else msg.content
            val estimated = clipped.length + (if (!msg.images.isNullOrEmpty()) 1_000 else 0)
            if (budgetedReversed.isNotEmpty() && usedChars + estimated > maxContextChars) break
            budgetedReversed.add(msg.copy(content = clipped))
            usedChars += estimated
        }
        val finalMessages = budgetedReversed.asReversed()

        android.util.Log.d(
            "AiService",
            "buildOpenAIBody: ${messages.size} msgs → ${finalMessages.size} msgs, chars=$usedChars, roles=${finalMessages.joinToString(",") { it.role }}"
        )

        return buildJsonObject {
            put("model", config.model)
            if (includeSampling) {
                put("max_tokens", config.maxTokens)
                put("temperature", config.temperature)
            }
            if (stream) put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", config.systemPrompt)
                }
                finalMessages.forEach { pm ->
                    addJsonObject {
                        put("role", pm.role)
                        if (!pm.images.isNullOrEmpty() && pm.role == "user") {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", pm.content)
                                }
                                pm.images.forEach { img ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", if (img.startsWith("http")) img else "data:image/jpeg;base64,$img")
                                        }
                                    }
                                }
                            }
                        } else {
                            put("content", pm.content)
                        }
                    }
                }
            }
            if (includeTools && tools.isNotEmpty()) {
                if (includeToolChoice) {
                    put("tool_choice", "auto")
                }
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                putJsonObject("parameters") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        tool.parameters.forEach { (name, param) ->
                                            putJsonObject(name) {
                                                put("type", param.type)
                                                put("description", param.description)
                                                param.enum?.let { e ->
                                                    putJsonArray("enum") { e.forEach { add(it) } }
                                                }
                                            }
                                        }
                                    }
                                    putJsonArray("required") {
                                        tool.parameters.filter { it.value.required }.forEach { add(it.key) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shouldRetryWithRelaxedChatSettings(statusCode: Int, body: String): Boolean {
        if (statusCode != 400) return false
        val normalized = body.lowercase()
        return normalized.contains("invalid chat setting") ||
            normalized.contains("(2013)") ||
            normalized.contains(" 2013") ||
            (normalized.contains("badrequesterror") && normalized.contains("invalid params")) ||
            normalized.contains("context length") ||
            normalized.contains("maximum context")
    }

    /**
     * Retry plans for OpenAI-compatible APIs:
     * 1) 保留 tools，只缩短上下文；
     * 2) 仍失败时才关闭 tools 作为兜底。
     */
    private fun buildOpenAIRetryPlans(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
        allowDisableTools: Boolean = true,
    ): List<Pair<String, JsonObject>> {
        val keepToolsCompact = buildOpenAIBody(
            messages = messages,
            tools = tools,
            stream = stream,
            maxContextMessages = 10,
            maxContextChars = 6_000,
            includeSampling = false,
            includeTools = true,
            includeToolChoice = false,
        )
        val keepToolsUltraCompact = buildOpenAIBody(
            messages = messages,
            tools = tools,
            stream = stream,
            maxContextMessages = 8,
            maxContextChars = 4_000,
            includeSampling = false,
            includeTools = true,
            includeToolChoice = false,
        )

        val plans = mutableListOf<Pair<String, JsonObject>>(
            "保留工具+缩短上下文" to keepToolsCompact,
            "保留工具+极限压缩上下文" to keepToolsUltraCompact,
        )

        if (allowDisableTools) {
            val noTools = buildOpenAIBody(
                messages = messages,
                tools = tools,
                stream = stream,
                maxContextMessages = 8,
                maxContextChars = 3_500,
                includeSampling = false,
                includeTools = false,
                includeToolChoice = false,
            )
            plans.add("关闭工具+极限压缩上下文" to noTools)
        }
        return plans
    }

    private fun buildClaudeBody(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("max_tokens", config.maxTokens)
        put("system", config.systemPrompt)
        if (stream) put("stream", true)
        putJsonArray("messages") {
            messages.filter { it.role != "tool" }.forEach { msg ->
                addJsonObject {
                    put("role", msg.role)
                    if (!msg.images.isNullOrEmpty() && msg.role == "user") {
                        putJsonArray("content") {
                            msg.images.forEach { img ->
                                addJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", img)
                                    }
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            }
                        }
                    } else {
                        put("content", msg.content)
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        putJsonObject("input_schema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                tool.parameters.forEach { (name, param) ->
                                    putJsonObject(name) {
                                        put("type", param.type)
                                        put("description", param.description)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseClaudeResponse(responseJson: JsonObject): ChatMessage {
        val contentBlocks = responseJson["content"]?.jsonArray ?: return ChatMessage("assistant", "")
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        contentBlocks.forEach { block ->
            val obj = block.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "text" -> textParts.add(obj["text"]?.jsonPrimitive?.content ?: "")
                "tool_use" -> {
                    toolCalls.add(ToolCall(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        arguments = obj["input"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                    ))
                }
            }
        }

        return ChatMessage(
            role = "assistant",
            content = textParts.joinToString("\n"),
            toolCalls = toolCalls.ifEmpty { null }
        )
    }

    /**
     * Parse tool calls embedded in text content.
     * Some providers (e.g. MiniMax) output tool calls in various text formats
     * instead of the standard OpenAI tool_calls JSON. This parser handles all known
     * variants with a single robust approach.
     */
    private fun parseToolCallsFromText(content: String): Pair<String, List<ToolCall>> {
        val toolCalls = mutableListOf<ToolCall>()
        var cleaned = content

        // --- Phase 1: Extract structured blocks first ---

        // Generic XML-style: anything with <parameter> tags preceded by a tool name
        // Covers: [TOOL_CALL]{tool=>"name"}...params...</invoke>, [name]...params..., etc.
        val xmlBlockPattern = Regex(
            """(?:\[TOOL_CALL\]\s*\{[^}]*?(?:tool\s*(?:=>|=|:)\s*"?([^"}\s]+)"?)[^}]*\}|""" +
            """\[([a-zA-Z_][a-zA-Z0-9_]*)\])""" +
            """\s*((?:<parameter\s[^>]*>[^<]*</parameter>\s*)+)""" +
            """(?:</invoke>\s*)?(?:</minimax:tool_call>\s*)?""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (match in xmlBlockPattern.findAll(cleaned)) {
            val rawName = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            if (rawName.isEmpty()) continue
            val paramBlock = match.groupValues[3]
            val params = mutableMapOf<String, String>()
            Regex("""<parameter\s+name="([^"]+)">([^<]*)</parameter>""").findAll(paramBlock).forEach {
                params[it.groupValues[1]] = it.groupValues[2]
            }
            if (params.isNotEmpty()) {
                toolCalls.add(ToolCall(name = resolveToolName(rawName), arguments = params))
                cleaned = cleaned.replace(match.value, "").trim()
            }
        }

        // JSON inside XML tags: <tool_call>JSON</tool_call> or <minimax:tool_call>JSON</minimax:tool_call>
        val xmlJsonPattern = Regex(
            """<(?:minimax:)?tool_call>\s*(\{.*?\})\s*</(?:minimax:)?tool_call>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in xmlJsonPattern.findAll(cleaned)) {
            val tc = tryParseJsonToolCall(match.groupValues[1])
            if (tc != null) {
                toolCalls.add(tc)
                cleaned = cleaned.replace(match.value, "").trim()
            }
        }

        // --- Phase 2: JSON-based patterns ---

        // {"tool_calls": [...]}
        val toolCallsArrayPattern = Regex("""\{\s*"tool_calls"\s*:\s*\[(.*?)\]\s*\}""", RegexOption.DOT_MATCHES_ALL)
        for (match in toolCallsArrayPattern.findAll(cleaned)) {
            try {
                val arr = json.parseToJsonElement("[${match.groupValues[1]}]").jsonArray
                for (item in arr) {
                    val tc = tryParseJsonToolCall(item.toString())
                    if (tc != null) toolCalls.add(tc)
                }
                cleaned = cleaned.replace(match.value, "").trim()
            } catch (_: Exception) { }
        }

        // [tool_name] {"key": "value"}
        val bracketJsonPattern = Regex("""\[([a-zA-Z_][a-zA-Z0-9_]*)\]\s*(\{[^\n]+\})""")
        for (match in bracketJsonPattern.findAll(cleaned)) {
            try {
                val rawName = match.groupValues[1]
                val args = json.parseToJsonElement(match.groupValues[2]).jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
                toolCalls.add(ToolCall(name = resolveToolName(rawName), arguments = args))
                cleaned = cleaned.replace(match.value, "").trim()
            } catch (_: Exception) { }
        }

        // --- Phase 3: Catch-all for any remaining {"name":"...", "arguments":{...}} ---
        val standaloneJsonPattern = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*(\{[^}]*\})\s*\}""")
        for (match in standaloneJsonPattern.findAll(cleaned)) {
            val tc = tryParseJsonToolCall(match.value)
            if (tc != null) {
                toolCalls.add(tc)
                cleaned = cleaned.replace(match.value, "").trim()
            }
        }

        // --- Phase 4: function_name(arg1, arg2) style ---
        val funcCallPattern = Regex("""(device_[a-z_]+|scheduler_[a-z_]+|skill_[a-z_]+)\s*\(\s*([^)]*)\s*\)""")
        for (match in funcCallPattern.findAll(cleaned)) {
            val name = match.groupValues[1]
            val argsStr = match.groupValues[2].trim()
            val args = mutableMapOf<String, String>()
            // Try key=value or key="value" pairs
            Regex("""(\w+)\s*=\s*"?([^",)]+)"?""").findAll(argsStr).forEach {
                args[it.groupValues[1]] = it.groupValues[2].trim()
            }
            if (args.isNotEmpty()) {
                toolCalls.add(ToolCall(name = name, arguments = args))
                cleaned = cleaned.replace(match.value, "").trim()
            }
        }

        // --- Phase 5: Bare [tool_name] with no arguments ---
        if (toolCalls.isEmpty()) {
            val bareToolPattern = Regex("""\[([a-zA-Z_][a-zA-Z0-9_]*)\]""")
            for (match in bareToolPattern.findAll(cleaned)) {
                val resolved = resolveToolName(match.groupValues[1])
                // Only match if it resolves to a known tool prefix
                if (resolved.startsWith("device_") || resolved.startsWith("scheduler_") ||
                    resolved.startsWith("skill_")) {
                    toolCalls.add(ToolCall(name = resolved, arguments = emptyMap()))
                    cleaned = cleaned.replace(match.value, "").trim()
                }
            }
        }

        if (toolCalls.isNotEmpty()) {
            android.util.Log.d("AiService", "parseToolCallsFromText: found ${toolCalls.size} tool calls: ${toolCalls.map { "${it.name}(${it.arguments})" }}")
        }

        // Clean up leftover XML tags
        cleaned = cleaned.replace(Regex("""</?(invoke|minimax:tool_call|tool_call|parameter)[^>]*>"""), "").trim()

        return cleaned to toolCalls
    }

    /** Try to parse a JSON string as a tool call. */
    private fun tryParseJsonToolCall(jsonStr: String): ToolCall? {
        return try {
            val obj = json.parseToJsonElement(jsonStr.trim()).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val args = (obj["arguments"] ?: obj["params"] ?: obj["parameters"])
                ?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            ToolCall(name = resolveToolName(name), arguments = args)
        } catch (_: Exception) { null }
    }

    /** Resolve a possibly malformed tool name to the correct one. */
    private fun resolveToolName(raw: String): String {
        val trimmed = raw.trim()
        // Already correct format
        if (trimmed.contains('_') && trimmed.length > 3) return trimmed

        // Build lookup from known tool names (lowercase no-underscore → real name)
        val knownTools = listOf(
            "device_read_screen", "device_click_text", "device_click_xy", "device_input_text",
            "device_swipe", "device_press", "device_screenshot", "device_shell",
            "device_launch_app", "device_stop_app", "device_send_sms", "device_call",
            "scheduler_create", "scheduler_list", "scheduler_delete",
        )
        val lookup = knownTools.associateBy { it.replace("_", "").lowercase() }
        val normalized = trimmed.replace("_", "").lowercase()
        lookup[normalized]?.let { return it }

        val inferredByPrefix = inferToolNameByPrefix(trimmed)
        if (strictToolNameResolution) {
            return inferredByPrefix ?: trimmed
        }

        // Fuzzy: find best match
        val best = knownTools.minByOrNull { levenshtein(it.replace("_", "").lowercase(), normalized) }
        if (best != null && levenshtein(best.replace("_", "").lowercase(), normalized) <= 3) {
            android.util.Log.d("AiService", "resolveToolName: '$trimmed' → '$best' (fuzzy)")
            return best
        }

        // Fallback: try to add underscores by prefix
        if (inferredByPrefix != null) return inferredByPrefix
        return trimmed
    }

    private fun inferToolNameByPrefix(raw: String): String? {
        val prefixes = listOf("device", "browser", "node", "scheduler", "sessions")
        for (prefix in prefixes) {
            if (raw.startsWith(prefix, ignoreCase = true) && raw.length > prefix.length) {
                val rest = raw.substring(prefix.length)
                val words = rest.fold(mutableListOf<StringBuilder>()) { acc, c ->
                    if (c.isUpperCase() || acc.isEmpty()) acc.add(StringBuilder().append(c.lowercaseChar()))
                    else acc.last().append(c)
                    acc
                }.joinToString("_")
                return if (words.isNotBlank()) "${prefix}_$words" else null
            }
        }
        return null
    }

    /** Simple Levenshtein distance for fuzzy tool name matching. */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }
}
