package com.phoneagent.engine

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.phoneagent.PhoneAgentApp
import com.phoneagent.ai.*
import com.phoneagent.device.DeviceController
import com.phoneagent.device.DeviceControllerManager
import com.phoneagent.memory.ConversationManager
import com.phoneagent.prompt.DefaultPrompts
import com.phoneagent.prompt.PromptTemplate
import com.phoneagent.scheduler.*
import com.phoneagent.voice.VoiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * AgentEngine: The central orchestrator — the brain of the personal AI assistant.
 *
 * Like OpenClaw's Pi agent runtime but running entirely on Android:
 * - Multi-session agent with session isolation
 * - Gateway integration (WebSocket + HTTP control plane)
 * - Multi-channel messaging (Telegram, SMS, webhooks)
 * - Skill marketplace (remote install + local built-in)
 * - Browser automation, device node commands
 * - Model failover, session pruning
 * - Cron/webhook/event automation
 * - Security: sandbox, allowlist, pairing
 */
class AgentEngine private constructor(private val context: Context) {

    private val app = context.applicationContext as PhoneAgentApp
    private val prefs = context.getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
    val aiService = AiService(loadAiConfig())
    val scheduler = TaskScheduler(context)
    val deviceManager = DeviceControllerManager(context)
    val conversationManager = ConversationManager(context)
    val voiceManager = VoiceManager(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _conversationHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val conversationHistory: StateFlow<List<ChatMessage>> = _conversationHistory

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    /** Text being streamed in real-time. Empty when not streaming. */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    /** Job reference for the current chat, used for cancellation. */
    private var currentChatJob: kotlinx.coroutines.Job? = null

    init {
        aiService.setStrictToolNameResolution(deviceManager.precisionMode.value)
        scope.launch {
            deviceManager.precisionMode.collect { enabled ->
                aiService.setStrictToolNameResolution(enabled)
            }
        }
    }

    companion object {
        @Volatile private var INSTANCE: AgentEngine? = null

        fun getInstance(context: Context): AgentEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        const val MAX_CONTEXT_MESSAGES = 50
        const val SUMMARY_TRIGGER_COUNT = 40
        const val MAX_ITERATIONS = 30
        const val STREAM_ROUND_TIMEOUT_MS = 300_000L
        const val NON_STREAM_ROUND_TIMEOUT_MS = 240_000L
        const val TOOL_EXEC_TIMEOUT_MS = 60_000L
        const val ASSISTANT_ROUND_RETRIES = 3
    }

    // ========================================================================
    //  Config
    // ========================================================================

    fun updateAiConfig(config: AiConfig) {
        aiService.updateConfig(config)
        saveAiConfig(config)
    }

    private fun saveAiConfig(config: AiConfig) {
        prefs.edit()
            .putString("provider", config.provider.name)
            .putString("api_key", config.apiKey)
            .putString("base_url", config.baseUrl)
            .putString("model", config.model)
            .putInt("max_tokens", config.maxTokens)
            .putFloat("temperature", config.temperature.toFloat())
            .apply()
    }

    private fun loadAiConfig(): AiConfig {
        val providerName = prefs.getString("provider", null)
        android.util.Log.d("AgentEngine", "loadAiConfig: providerName=$providerName")
        if (providerName == null) return AiConfig()
        return try {
            val provider = AiProvider.valueOf(providerName)
            val cfg = AiConfig(
                provider = provider,
                apiKey = prefs.getString("api_key", "") ?: "",
                baseUrl = prefs.getString("base_url", AiConfig.PRESETS[provider]?.baseUrl ?: "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
                model = prefs.getString("model", AiConfig.PRESETS[provider]?.defaultModel ?: "gpt-4o") ?: "gpt-4o",
                maxTokens = prefs.getInt("max_tokens", 4096),
                temperature = prefs.getFloat("temperature", 0.7f).toDouble(),
            )
            android.util.Log.d("AgentEngine", "loadAiConfig: loaded provider=${cfg.provider}, model=${cfg.model}, baseUrl=${cfg.baseUrl}")
            cfg
        } catch (e: Exception) {
            android.util.Log.e("AgentEngine", "loadAiConfig failed for '$providerName'", e)
            AiConfig()
        }
    }

    // ========================================================================
    //  Conversation management
    // ========================================================================

    suspend fun newConversation(title: String = "新对话", systemPrompt: String = ""): Long {
        val id = conversationManager.createConversation(title, systemPrompt)
        _conversationHistory.value = emptyList()
        return id
    }

    suspend fun switchConversation(conversationId: Long) {
        val messages = conversationManager.switchConversation(conversationId)
        _conversationHistory.value = messages
    }

    suspend fun deleteConversation(conversationId: Long) {
        conversationManager.deleteConversation(conversationId)
        if (conversationManager.currentConversationId <= 0) {
            _conversationHistory.value = emptyList()
        }
    }

    /** Apply a prompt template to a new conversation. */
    suspend fun applyPromptTemplate(template: PromptTemplate): Long {
        val id = conversationManager.createConversation(
            title = template.name,
            systemPrompt = template.systemPrompt,
        )
        _conversationHistory.value = emptyList()
        return id
    }

    /** Update the system prompt for the current conversation. */
    suspend fun updateCurrentSystemPrompt(prompt: String) {
        conversationManager.updateSystemPrompt(prompt)
    }

    fun clearHistory() { _conversationHistory.value = emptyList() }

    // ========================================================================
    //  Chat (non-streaming — for agent tool loop)
    // ========================================================================

    suspend fun chat(userMessage: String, images: List<String>? = null): String {
        _isProcessing.value = true
        _streamingText.value = ""

        if (conversationManager.currentConversationId <= 0) {
            conversationManager.createConversation()
        }

        val userMsg = ChatMessage(role = "user", content = userMessage, images = images)
        conversationManager.saveMessage(userMsg)

        val messages = _conversationHistory.value.toMutableList()
        messages.add(userMsg)
        _conversationHistory.value = messages.toList()

        try {
            var iterationCount = 0
            var executedToolInRun = false
            while (iterationCount < MAX_ITERATIONS) {
                iterationCount++

                val contextMessages = conversationManager.getContextMessages(MAX_CONTEXT_MESSAGES)
                val aiMessages = if (contextMessages.size < messages.size) contextMessages else messages
                val tools = gatherAllTools()

                val baseResponse = requestAssistantRoundNonStreaming(
                    aiMessages = aiMessages,
                    tools = tools,
                )
                val response = recoverToolCallIfNeeded(
                    userMessage = userMessage,
                    aiMessages = aiMessages,
                    tools = tools,
                    response = baseResponse,
                    iteration = iterationCount,
                    executedToolInRun = executedToolInRun
                )
                conversationManager.saveMessage(response)
                messages.add(response)
                _conversationHistory.value = messages.toList()

                if (response.toolCalls.isNullOrEmpty()) {
                    maybeSummarize()
                    return response.content
                }

                for (toolCall in response.toolCalls) {
                    executedToolInRun = true
                    val result = executeToolSafely(toolCall.name, toolCall.arguments)
                    val toolMsg = ChatMessage(role = "tool", content = "[${toolCall.name}] $result")
                    conversationManager.saveMessage(toolMsg)
                    messages.add(toolMsg)
                }
                _conversationHistory.value = messages.toList()
            }
            return "已达到最大执行轮数，请简化指令"
        } catch (e: Exception) {
            val error = "执行出错: ${e.message}"
            val errMsg = ChatMessage(role = "assistant", content = error)
            conversationManager.saveMessage(errMsg)
            messages.add(errMsg)
            _conversationHistory.value = messages.toList()
            return error
        } finally {
            _isProcessing.value = false
        }
    }

    /** Strip <think>...</think> tags from model responses (reasoning tokens). */
    private fun stripThinkTags(text: String): String {
        return text.replace(Regex("""<think>[\s\S]*?</think>\s*"""), "").trimStart()
    }

    private fun isTransientAssistantRoundFailure(response: ChatMessage): Boolean {
        if (!response.toolCalls.isNullOrEmpty()) return false
        val content = response.content.trim()
        if (content.isEmpty()) return true
        return content.startsWith("API返回错误(") ||
            content.startsWith("AI请求失败:") ||
            content.startsWith("解析响应失败:") ||
            content.contains("invalid chat setting", ignoreCase = true) ||
            content.contains("请求超时") ||
            content.contains("timed out", ignoreCase = true)
    }

    private fun isLikelyAutomationRequest(text: String): Boolean {
        val keywords = listOf(
            "打开", "启动", "发送", "点击", "输入", "滑动", "返回", "搜索", "读取", "截图",
            "launch", "open", "send", "click", "type", "swipe", "back", "search", "screenshot"
        )
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun looksLikeCompletionText(text: String): Boolean {
        val doneKeywords = listOf(
            "已完成", "完成了", "已发送", "发送成功", "已经发送", "已执行", "任务完成",
            "done", "completed", "success", "finished"
        )
        return doneKeywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun shouldRecoverMissingToolCall(
        userMessage: String,
        response: ChatMessage,
        iteration: Int,
        executedToolInRun: Boolean,
    ): Boolean {
        if (!response.toolCalls.isNullOrEmpty()) return false
        if (iteration >= MAX_ITERATIONS) return false
        val content = response.content.trim()
        if (looksLikeCompletionText(content)) return false

        val automationIntent = executedToolInRun || isLikelyAutomationRequest(userMessage)
        if (!automationIntent) return false

        // Model is still "planning"/"describing next step" but forgot tool_calls.
        val planningHints = listOf("让我", "我需要", "接下来", "然后", "先", "下一步", "需要点击", "需要打开")
        val actionHints = listOf("点击", "打开", "输入", "发送", "搜索", "返回", "进入", "读取", "查看")
        val looksLikePlan = planningHints.any { content.contains(it) } ||
            actionHints.any { content.contains(it) } ||
            content.length < 16
        return looksLikePlan
    }

    private suspend fun recoverToolCallIfNeeded(
        userMessage: String,
        aiMessages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        response: ChatMessage,
        iteration: Int,
        executedToolInRun: Boolean,
    ): ChatMessage {
        if (!shouldRecoverMissingToolCall(userMessage, response, iteration, executedToolInRun)) {
            return response
        }

        val nudge = ChatMessage(
            role = "user",
            content = "继续执行当前设备任务。不要解释，不要总结，直接调用下一步最合适的工具。如果不确定，先调用 device_read_screen。"
        )
        val recovered = requestAssistantRoundNonStreaming(aiMessages + nudge, tools)
        return if (!recovered.toolCalls.isNullOrEmpty()) {
            android.util.Log.w("AgentEngine", "Recovered missing tool_call at iteration $iteration")
            recovered
        } else if (isLikelyAutomationRequest(userMessage) && iteration < MAX_ITERATIONS) {
            android.util.Log.w("AgentEngine", "Recovery still missing tool_call at iteration $iteration, inject device_read_screen")
            response.copy(
                content = if (response.content.isBlank()) "（自动补救：读取屏幕）" else response.content,
                toolCalls = listOf(ToolCall(name = "device_read_screen", arguments = emptyMap()))
            )
        } else {
            response
        }
    }

    private suspend fun requestAssistantRound(
        aiMessages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        allowStreaming: Boolean,
    ): ChatMessage {
        var lastResponse: ChatMessage? = null
        for (attempt in 1..ASSISTANT_ROUND_RETRIES) {
            val useStreaming = allowStreaming && attempt == 1
            val response = if (useStreaming) {
                requestAssistantRoundStreaming(aiMessages, tools)
            } else {
                requestAssistantRoundNonStreaming(aiMessages, tools)
            }
            lastResponse = response

            if (!isTransientAssistantRoundFailure(response)) {
                return response
            }
            android.util.Log.w(
                "AgentEngine",
                "assistant round transient failure, retry attempt=$attempt/$ASSISTANT_ROUND_RETRIES, content='${response.content.take(120)}'"
            )
            kotlinx.coroutines.delay((attempt * 500L).coerceAtMost(1500L))
        }
        return lastResponse ?: ChatMessage(
            role = "assistant",
            content = "请求失败：未获得有效响应，请稍后重试。"
        )
    }

    private suspend fun requestAssistantRoundStreaming(
        aiMessages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage {
        _streamingText.value = ""
        var completedMessage: ChatMessage? = null
        val streamBuffer = StringBuilder()

        val streamFinished = withTimeoutOrNull(STREAM_ROUND_TIMEOUT_MS) {
            aiService.chatStream(
                messages = aiMessages,
                tools = tools,
                mustKeepTools = tools.isNotEmpty()
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        streamBuffer.append(event.text)
                        _streamingText.value = stripThinkTags(streamBuffer.toString())
                    }
                    is StreamEvent.Complete -> {
                        completedMessage = event.message
                    }
                }
            }
            true
        } ?: false

        _streamingText.value = ""
        if (!streamFinished) {
            android.util.Log.w("AgentEngine", "chatStream: streaming round timed out")
            return ChatMessage(
                role = "assistant",
                content = "请求超时，正在切换稳定模式重试。"
            )
        }

        val rawResponse = completedMessage ?: ChatMessage("assistant", streamBuffer.toString())
        val cleanContent = stripThinkTags(rawResponse.content)
        return rawResponse.copy(content = cleanContent)
    }

    private suspend fun requestAssistantRoundNonStreaming(
        aiMessages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage {
        val response = withTimeoutOrNull(NON_STREAM_ROUND_TIMEOUT_MS) {
            aiService.chat(
                messages = aiMessages,
                tools = tools,
                mustKeepTools = tools.isNotEmpty()
            )
        } ?: ChatMessage(
            role = "assistant",
            content = "请求超时，稳定模式也未在时限内返回。"
        )
        return response.copy(content = stripThinkTags(response.content))
    }

    private suspend fun executeToolSafely(
        toolName: String,
        arguments: Map<String, String>,
    ): String {
        return try {
            withTimeout(TOOL_EXEC_TIMEOUT_MS) {
                executeTool(toolName, arguments)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            "Error: 工具执行超时（>${TOOL_EXEC_TIMEOUT_MS / 1000}s）"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: 工具执行失败 - ${e.message}"
        }
    }

    // ========================================================================
    //  Streaming chat
    // ========================================================================

    /**
     * Chat with streaming output. Emits text deltas via [streamingText].
     * Handles tool calls: streams text, then falls back to non-streaming for tool execution,
     * then streams the final response.
     */
    suspend fun chatStream(userMessage: String, images: List<String>? = null) {
        android.util.Log.d("AgentEngine", "chatStream called: '$userMessage', provider=${aiService.currentConfig.provider}, keyLen=${aiService.currentConfig.apiKey.length}")
        currentChatJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        _isProcessing.value = true
        _streamingText.value = ""

        val messages = _conversationHistory.value.toMutableList()

        try {
            // Check API key before making request
            if (aiService.currentConfig.apiKey.isEmpty()) {
                val errMsg = ChatMessage(role = "assistant", content = "⚠️ 请先在设置中配置 API Key，点击右上角菜单 → 设置")
                val userMsg = ChatMessage(role = "user", content = userMessage, images = images)
                messages.add(userMsg)
                messages.add(errMsg)
                _conversationHistory.value = messages.toList()
                return
            }

            if (conversationManager.currentConversationId <= 0) {
                conversationManager.createConversation()
            }

            val userMsg = ChatMessage(role = "user", content = userMessage, images = images)
            android.util.Log.d("AgentEngine", "chatStream: saving user message")
            conversationManager.saveMessage(userMsg)

            messages.add(userMsg)
            _conversationHistory.value = messages.toList()
            var iterationCount = 0
            var executedToolInRun = false
            while (iterationCount < MAX_ITERATIONS) {
                iterationCount++
                android.util.Log.d("AgentEngine", "chatStream: iteration $iterationCount")

                val contextMessages = conversationManager.getContextMessages(MAX_CONTEXT_MESSAGES)
                val aiMessages = if (contextMessages.size < messages.size) contextMessages else messages
                val tools = gatherAllTools()
                val allowStreaming = iterationCount == 1
                android.util.Log.d(
                    "AgentEngine",
                    "chatStream: requesting assistant round with ${aiMessages.size} msgs, ${tools.size} tools, allowStreaming=$allowStreaming"
                )
                val response = requestAssistantRound(
                    aiMessages = aiMessages,
                    tools = tools,
                    allowStreaming = allowStreaming
                )
                val recoveredResponse = recoverToolCallIfNeeded(
                    userMessage = userMessage,
                    aiMessages = aiMessages,
                    tools = tools,
                    response = response,
                    iteration = iterationCount,
                    executedToolInRun = executedToolInRun
                )
                android.util.Log.d("AgentEngine", "chatStream: response content='${recoveredResponse.content.take(100)}', toolCalls=${recoveredResponse.toolCalls?.size ?: 0}, streamingLen=${_streamingText.value.length}")
                _streamingText.value = ""

                try { conversationManager.saveMessage(recoveredResponse) } catch (_: Exception) {}
                messages.add(recoveredResponse)
                _conversationHistory.value = messages.toList()
                android.util.Log.d("AgentEngine", "chatStream: history size=${messages.size}")

                if (recoveredResponse.toolCalls.isNullOrEmpty()) {
                    maybeSummarize()
                    return
                }

                // Execute tools (non-streaming)
                for (toolCall in recoveredResponse.toolCalls) {
                    executedToolInRun = true
                    android.util.Log.d("AgentEngine", "chatStream: executing tool ${toolCall.name}")
                    val result = executeToolSafely(toolCall.name, toolCall.arguments)
                    android.util.Log.d("AgentEngine", "chatStream: tool result='${result.take(100)}'")
                    val toolMsg = ChatMessage(role = "tool", content = "[${toolCall.name}] $result")
                    try { conversationManager.saveMessage(toolMsg) } catch (_: Exception) {}
                    messages.add(toolMsg)
                }
                _conversationHistory.value = messages.toList()
            }

            val limitMsg = ChatMessage(
                role = "assistant",
                content = "已达到最大执行轮数（$MAX_ITERATIONS），为避免长时间卡住，本次任务已停止。请尝试简化指令或分步执行。"
            )
            try { conversationManager.saveMessage(limitMsg) } catch (_: Exception) {}
            messages.add(limitMsg)
            _conversationHistory.value = messages.toList()
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.d("AgentEngine", "chatStream cancelled by user")
            _streamingText.value = ""
            val stopMsg = ChatMessage(role = "assistant", content = "⏹ 已停止")
            try { conversationManager.saveMessage(stopMsg) } catch (_: Exception) {}
            messages.add(stopMsg)
            _conversationHistory.value = messages.toList()
        } catch (e: Exception) {
            android.util.Log.e("AgentEngine", "chatStream error", e)
            _streamingText.value = ""
            val error = "执行出错: ${e.message}"
            val errMsg = ChatMessage(role = "assistant", content = error)
            try { conversationManager.saveMessage(errMsg) } catch (_: Exception) {}
            messages.add(errMsg)
            _conversationHistory.value = messages.toList()
        } finally {
            _isProcessing.value = false
            _streamingText.value = ""
            currentChatJob = null
        }
    }

    /** Cancel the currently running chat/agent loop. */
    fun cancelChat() {
        android.util.Log.d("AgentEngine", "cancelChat called")
        currentChatJob?.cancel()
        currentChatJob = null
    }

    // ========================================================================
    //  Message actions
    // ========================================================================

    /** Regenerate the last assistant response. */
    suspend fun regenerateLastResponse() {
        val messages = _conversationHistory.value.toMutableList()

        // Remove messages from end until we hit the last user message
        while (messages.isNotEmpty() && messages.last().role != "user") {
            messages.removeAt(messages.lastIndex)
        }

        if (messages.isEmpty()) return

        // Get the last user message and re-send
        val lastUserMsg = messages.last().content
        val lastUserImages = messages.last().images
        messages.removeAt(messages.lastIndex)
        _conversationHistory.value = messages.toList()

        chatStream(lastUserMsg, lastUserImages)
    }

    /** Delete a specific message by index in the current conversation history. */
    fun deleteMessageAt(index: Int) {
        val messages = _conversationHistory.value.toMutableList()
        if (index in messages.indices) {
            messages.removeAt(index)
            _conversationHistory.value = messages.toList()
        }
    }

    // ========================================================================
    //  Image generation
    // ========================================================================

    suspend fun generateImage(prompt: String): String {
        _isProcessing.value = true
        try {
            if (conversationManager.currentConversationId <= 0) {
                conversationManager.createConversation()
            }

            val userMsg = ChatMessage(role = "user", content = "生成图片: $prompt")
            conversationManager.saveMessage(userMsg)
            val messages = _conversationHistory.value.toMutableList()
            messages.add(userMsg)
            _conversationHistory.value = messages.toList()

            val imageUrl = aiService.generateImage(prompt)
            val resultMsg = ChatMessage(
                role = "assistant",
                content = "已生成图片",
                imageUrl = imageUrl,
            )
            conversationManager.saveMessage(resultMsg)
            messages.add(resultMsg)
            _conversationHistory.value = messages.toList()
            return imageUrl
        } catch (e: Exception) {
            val error = "图片生成失败: ${e.message}"
            val errMsg = ChatMessage(role = "assistant", content = error)
            val messages = _conversationHistory.value.toMutableList()
            messages.add(errMsg)
            _conversationHistory.value = messages.toList()
            return error
        } finally {
            _isProcessing.value = false
        }
    }

    // ========================================================================
    //  Export / Share
    // ========================================================================

    /** Export current conversation as markdown text. */
    fun exportConversationAsMarkdown(): String {
        val messages = _conversationHistory.value
        if (messages.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("# 对话记录")
        sb.appendLine()
        messages.forEach { msg ->
            val role = when (msg.role) {
                "user" -> "👤 用户"
                "assistant" -> "🤖 助手"
                "tool" -> "🔧 工具"
                "system" -> "⚙️ 系统"
                else -> msg.role
            }
            sb.appendLine("### $role")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    /** Share conversation via system share intent. */
    fun shareConversation() {
        val text = exportConversationAsMarkdown()
        if (text.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "PhoneAgent 对话记录")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "分享对话").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    /** Used by scheduler. */
    suspend fun executeCommand(command: String): String = chat(command)

    // ========================================================================
    //  Auto-summarize
    // ========================================================================

    private suspend fun maybeSummarize() {
        val count = _conversationHistory.value.size
        if (count >= SUMMARY_TRIGGER_COUNT) {
            val existingSummary = conversationManager.getCurrentSummary()
            val recentText = _conversationHistory.value
                .filter { it.role in listOf("user", "assistant") }
                .takeLast(20)
                .joinToString("\n") { "${it.role}: ${it.content.take(200)}" }

            val summaryPrompt = if (existingSummary.isNotEmpty()) {
                "之前的摘要: $existingSummary\n\n最近对话:\n$recentText\n\n请用3-5句话总结以上所有对话的关键信息。"
            } else {
                "以下是对话记录:\n$recentText\n\n请用3-5句话总结以上对话的关键信息。"
            }

            try {
                val summaryResponse = aiService.chat(
                    listOf(ChatMessage(role = "user", content = summaryPrompt))
                )
                conversationManager.updateSummary(summaryResponse.content)
            } catch (_: Exception) { }
        }
    }

    // ========================================================================
    //  Tool gathering
    // ========================================================================

    private suspend fun gatherAllTools(): List<ToolDefinition> {
        val tools = mutableListOf<ToolDefinition>()
        // Device control — core tools
        tools.addAll(getDeviceTools())
        // Scheduler
        tools.addAll(getSchedulerTools())
        // Built-in skills (exclude those that duplicate device_* tools)
        val skipSkillPatterns = setOf("launch", "sms", "call", "open_url")
        val skillTools = app.skillRegistry.getAllTools()
            .filter { tool -> skipSkillPatterns.none { p -> tool.name.contains(p, ignoreCase = true) } }
        tools.addAll(skillTools)
        android.util.Log.d("AgentEngine", "gatherAllTools: total=${tools.size}")
        return tools
    }

    private fun getDeviceTools(): List<ToolDefinition> = listOf(
        ToolDefinition("device_read_screen", "读取当前屏幕上的所有文字和UI元素"),
        ToolDefinition("device_click_text", "点击屏幕上包含指定文字的元素",
            mapOf("text" to ToolParameter("string", "要点击的文字", required = true))),
        ToolDefinition("device_click_xy", "点击屏幕上的指定坐标",
            mapOf("x" to ToolParameter("string", "X坐标", required = true),
                  "y" to ToolParameter("string", "Y坐标", required = true))),
        ToolDefinition("device_input_text", "在当前焦点输入框中输入文字",
            mapOf("text" to ToolParameter("string", "要输入的文字", required = true))),
        ToolDefinition("device_swipe", "滑动屏幕",
            mapOf("direction" to ToolParameter("string", "方向", required = true,
                enum = listOf("up", "down", "left", "right")))),
        ToolDefinition("device_press", "按下系统按键",
            mapOf("key" to ToolParameter("string", "按键", required = true,
                enum = listOf("back", "home", "recents", "notifications", "power", "volume_up", "volume_down", "enter")))),
        ToolDefinition("device_screenshot", "截取当前屏幕截图",
            mapOf("path" to ToolParameter("string", "保存路径（可选）"))),
        ToolDefinition("device_shell", "执行Shell命令（需要Shell/Root/Shizuku权限）",
            mapOf("command" to ToolParameter("string", "Shell命令", required = true))),
        ToolDefinition("device_launch_app", "通过包名启动应用",
            mapOf("package" to ToolParameter("string", "应用包名", required = true))),
        ToolDefinition("device_stop_app", "强制停止应用",
            mapOf("package" to ToolParameter("string", "应用包名", required = true))),
        ToolDefinition("device_send_sms", "发送短信给指定号码",
            mapOf("to" to ToolParameter("string", "接收号码", required = true),
                  "text" to ToolParameter("string", "短信内容", required = true))),
        ToolDefinition("device_call", "拨打电话",
            mapOf("number" to ToolParameter("string", "电话号码", required = true))),
    )

    private fun getSchedulerTools(): List<ToolDefinition> = listOf(
        ToolDefinition("scheduler_create", "创建定时任务",
            mapOf("name" to ToolParameter("string", "任务名称", required = true),
                  "command" to ToolParameter("string", "要执行的命令", required = true),
                  "repeat" to ToolParameter("string", "重复模式", enum = listOf("once","daily","weekly","weekdays","interval")),
                  "time" to ToolParameter("string", "执行时间 HH:mm 格式"),
                  "interval_minutes" to ToolParameter("string", "间隔分钟数（interval模式）"))),
        ToolDefinition("scheduler_list", "列出所有定时任务"),
        ToolDefinition("scheduler_delete", "删除定时任务",
            mapOf("task_id" to ToolParameter("string", "任务ID", required = true))),
    )

    // ========================================================================
    //  Tool execution
    // ========================================================================

    private suspend fun executeTool(name: String, arguments: Map<String, String>): String {
        return when {
            name.startsWith("skill_") -> app.skillRegistry.executeTool(name, arguments)
            name.startsWith("device_") -> executeDeviceTool(name, arguments)
            name.startsWith("scheduler_") -> executeSchedulerTool(name, arguments)
            else -> "Unknown tool: $name"
        }
    }

    private fun isPrecisionModeEnabled(): Boolean = deviceManager.precisionMode.value

    private fun controllersForDeviceAction(shellPreferred: Boolean = false): List<DeviceController> {
        return if (isPrecisionModeEnabled()) {
            deviceManager.getControllersForRetry(shellPreferred = shellPreferred)
        } else {
            listOf(deviceManager.getActiveController())
        }
    }

    private suspend fun runDeviceActionWithRetry(
        actionName: String,
        shellPreferred: Boolean = false,
        block: suspend (DeviceController) -> Result<String>,
    ): String {
        val controllers = controllersForDeviceAction(shellPreferred = shellPreferred)
        val errors = mutableListOf<String>()

        for ((index, ctrl) in controllers.withIndex()) {
            val result = try {
                block(ctrl)
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (result.isSuccess) {
                val output = result.getOrNull().orEmpty().ifBlank { "OK" }
                if (index > 0 && isPrecisionModeEnabled()) {
                    Log.w("AgentEngine", "$actionName succeeded by fallback controller=${ctrl.name}")
                    return "$output（已回退到${ctrl.name}）"
                }
                return output
            }

            val err = result.exceptionOrNull()?.message ?: "unknown error"
            Log.w("AgentEngine", "$actionName failed on ${ctrl.name}: $err")
            errors.add("${ctrl.name}: $err")
            if (!isPrecisionModeEnabled()) break
        }

        return "Error: ${errors.joinToString(" | ").ifEmpty { "unknown failure" }}"
    }

    private suspend fun readScreenWithFallback(controllers: List<DeviceController>): String {
        for (ctrl in controllers) {
            val text = ctrl.readScreen().getOrElse { "" }
            if (isUsefulScreenText(text)) return text

            val a11y = com.phoneagent.device.AgentAccessibilityService.getInstance()
            val a11yText = a11y?.readScreen() ?: ""
            if (isUsefulScreenText(a11yText)) return a11yText

            Log.d("AgentEngine", "readScreen: ${ctrl.name} result too short (${text.length}), trying uiautomator dump")
            val dumpResult = try {
                val dumpPath = "/sdcard/window_dump.xml"
                ctrl.runShellCommand("uiautomator dump $dumpPath").getOrNull()
                kotlinx.coroutines.delay(900)
                val catResult = ctrl.runShellCommand("cat $dumpPath").getOrNull() ?: ""
                ctrl.runShellCommand("rm $dumpPath")
                if (catResult.length > 50) parseUiAutomatorDump(catResult) else ""
            } catch (e: Exception) {
                Log.w("AgentEngine", "readScreen uiautomator fallback failed on ${ctrl.name}", e)
                ""
            }
            if (isUsefulScreenText(dumpResult)) return dumpResult
        }

        val a11y = com.phoneagent.device.AgentAccessibilityService.getInstance()
        val pkg = a11y?.getActivePackage() ?: "unknown"
        val shellCtrl = deviceManager.getControllersForRetry(shellPreferred = true).firstOrNull()
        val dumpsysRaw = if (shellCtrl != null) {
            try {
                shellCtrl.runShellCommand("dumpsys activity top | head -5").getOrNull() ?: ""
            } catch (_: Exception) { "" }
        } else ""
        val dumpsys = if (dumpsysRaw.contains("Permission Denial", ignoreCase = true)) "" else dumpsysRaw

        return "当前应用: $pkg\n" +
            "屏幕内容无法通过无障碍服务读取(该应用可能不支持)。\n" +
            "建议：优先开启精准模式，或启用 Shizuku/Root 后重试 device_read_screen；也可使用 device_screenshot 或坐标操作。\n" +
            if (dumpsys.isNotEmpty()) "Activity信息: ${dumpsys.take(200)}" else ""
    }

    private fun isUsefulScreenText(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length < 50) return false
        if (!text.contains("@[")) return false

        // Multi-window mode from accessibility: require at least one non-systemui window
        // that contains actionable node output, otherwise keep falling back.
        if (text.contains("--- [window#")) {
            val sectionRegex = Regex(
                """--- \[window#\d+\]\[([^\]]+)\] ---([\s\S]*?)(?=--- \[window#\d+\]\[|$)"""
            )
            val sections = sectionRegex.findAll(text).toList()
            if (sections.isNotEmpty()) {
                val hasUsefulAppSection = sections.any { m ->
                    val pkg = m.groupValues[1]
                    val body = m.groupValues[2]
                    pkg != "com.android.systemui" && body.contains("@[")
                }
                if (!hasUsefulAppSection) return false
            }
        }
        return true
    }

    private fun buildShellInputCommand(text: String): String {
        val escaped = text.replace("'", "'\\''")
        return if (text.all { it.code < 128 }) {
            "input text '${escaped.replace(" ", "%s")}'"
        } else {
            "am broadcast -a ADB_INPUT_TEXT --es msg '$escaped'"
        }
    }

    private suspend fun tryInputTextWithController(ctrl: DeviceController, text: String): Result<String> {
        val primary = ctrl.inputText(text)
        if (primary.isSuccess) return primary

        val a11y = com.phoneagent.device.AgentAccessibilityService.getInstance()
        if (a11y != null && pasteViaClipboard(a11y, text)) {
            return Result.success("已输入(通过粘贴)")
        }

        return try {
            ctrl.runShellCommand(buildShellInputCommand(text)).map {
                if (it.isBlank() || it == "OK") "已输入(通过Shell)" else it
            }
        } catch (e: Exception) {
            Result.failure(Exception("所有输入方式均失败 - ${e.message}"))
        }
    }

    /**
     * Returns:
     * - true: text appears on current screen
     * - false: screen readable but text not found
     * - null: screen cannot be reliably read for verification
     */
    private suspend fun verifyTextInputApplied(text: String): Boolean? {
        val normalizedToken = text.replace("\\s+".toRegex(), "").trim()
        if (normalizedToken.length < 2) return null
        val token = if (normalizedToken.length > 12) normalizedToken.takeLast(12) else normalizedToken

        var screen = ""
        for (ctrl in controllersForDeviceAction()) {
            val content = ctrl.readScreen().getOrElse { "" }
            if (content.length > 30) {
                screen = content
                break
            }
        }
        if (screen.length <= 30) {
            screen = com.phoneagent.device.AgentAccessibilityService.getInstance()?.readScreen().orEmpty()
        }

        if (screen.length <= 30) return null
        val normalizedScreen = screen.replace("\\s+".toRegex(), "")
        return normalizedScreen.contains(token)
    }

    private suspend fun executeDeviceTool(name: String, arguments: Map<String, String>): String {
        return when (name) {
            "device_read_screen" -> {
                val readControllers =
                    (controllersForDeviceAction() + deviceManager.getControllersForRetry(shellPreferred = true))
                        .distinctBy { it.name }
                readScreenWithFallback(readControllers)
            }
            "device_click_text" -> {
                val text = arguments["text"] ?: return "Error: missing text"
                val result = runDeviceActionWithRetry(name) { ctrl ->
                    ctrl.clickByText(text).map { it.ifBlank { "已点击: $text" } }
                }
                kotlinx.coroutines.delay(500) // wait for UI to respond
                result
            }
            "device_click_xy" -> {
                val x = arguments["x"]?.toIntOrNull() ?: return "Error: invalid x"
                val y = arguments["y"]?.toIntOrNull() ?: return "Error: invalid y"
                val result = runDeviceActionWithRetry(name) { ctrl ->
                    ctrl.clickAt(x, y).map { it.ifBlank { "已点击: ($x, $y)" } }
                }
                kotlinx.coroutines.delay(500) // wait for UI to respond
                result
            }
            "device_input_text" -> {
                val text = arguments["text"] ?: return "Error: missing text"
                val result = runDeviceActionWithRetry(name) { ctrl -> tryInputTextWithController(ctrl, text) }
                if (!isPrecisionModeEnabled()) return result

                kotlinx.coroutines.delay(350)
                when (verifyTextInputApplied(text)) {
                    true, null -> result
                    false -> {
                        val a11y = com.phoneagent.device.AgentAccessibilityService.getInstance()
                        val pasted = if (a11y != null) pasteViaClipboard(a11y, text) else false
                        if (pasted) {
                            kotlinx.coroutines.delay(250)
                            val retried = verifyTextInputApplied(text)
                            if (retried != false) "已输入(精准模式二次重试)" else "Warning: 输入可能未生效，请先点击输入框后重试。上次结果: $result"
                        } else {
                            "Warning: 输入可能未生效，请先点击输入框后重试。上次结果: $result"
                        }
                    }
                }
            }
            "device_swipe" -> {
                val dir = arguments["direction"] ?: return "Error: missing direction"
                runDeviceActionWithRetry(name) { ctrl ->
                    val (w, h) = ctrl.getScreenSize()
                    val cx = w / 2
                    val cy = h / 2
                    val offset = minOf(w, h) / 3
                    when (dir) {
                        "up" -> ctrl.swipe(cx, cy + offset, cx, cy - offset)
                        "down" -> ctrl.swipe(cx, cy - offset, cx, cy + offset)
                        "left" -> ctrl.swipe(cx + offset, cy, cx - offset, cy)
                        "right" -> ctrl.swipe(cx - offset, cy, cx + offset, cy)
                        else -> Result.failure(Exception("Unknown direction: $dir"))
                    }
                }
            }
            "device_press" -> {
                val key = arguments["key"] ?: return "Error: missing key"
                runDeviceActionWithRetry(name) { ctrl -> ctrl.pressKey(key) }
            }
            "device_screenshot" -> {
                val path = arguments["path"] ?: "/sdcard/Pictures/screenshot_${System.currentTimeMillis()}.png"
                runDeviceActionWithRetry(name, shellPreferred = true) { ctrl -> ctrl.screenshot(path) }
            }
            "device_shell" -> {
                val command = arguments["command"] ?: return "Error: missing command"
                runDeviceActionWithRetry(name, shellPreferred = true) { ctrl -> ctrl.runShellCommand(command) }
            }
            "device_launch_app" -> {
                val pkg = arguments["package"] ?: return "Error: missing package"
                val result = runDeviceActionWithRetry(name) { ctrl -> ctrl.launchApp(pkg) }
                kotlinx.coroutines.delay(2000) // wait for app to launch and render
                result
            }
            "device_stop_app" -> {
                val pkg = arguments["package"] ?: return "Error: missing package"
                runDeviceActionWithRetry(name, shellPreferred = true) { ctrl -> ctrl.forceStopApp(pkg) }
            }
            "device_send_sms" -> {
                val to = arguments["to"] ?: return "Error: missing phone number"
                val text = arguments["text"] ?: return "Error: missing text"
                try {
                    @Suppress("DEPRECATION")
                    val smsManager = android.telephony.SmsManager.getDefault()
                    val parts = smsManager.divideMessage(text)
                    smsManager.sendMultipartTextMessage(to, null, parts, null, null)
                    "短信已发送到: $to"
                } catch (e: Exception) {
                    "Error: 发送失败 - ${e.message}"
                }
            }
            "device_call" -> {
                val number = arguments["number"] ?: return "Error: missing number"
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                        data = android.net.Uri.parse("tel:$number")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "正在拨打: $number"
                } catch (e: Exception) {
                    "Error: 拨打失败 - ${e.message}"
                }
            }
            else -> "Unknown device tool: $name"
        }
    }

    /** Parse uiautomator dump XML into readable text with coordinates. */
    private fun parseUiAutomatorDump(xml: String): String {
        val sb = StringBuilder()
        // Extract node elements from XML
        val allNodes = Regex("""<node\s([^>]+?)/?> """.trimEnd())
        for (match in allNodes.findAll(xml)) {
            val attrs = match.groupValues[1]
            val text = Regex("""text="([^"]*?)"""").find(attrs)?.groupValues?.get(1) ?: ""
            val desc = Regex("""content-desc="([^"]*?)"""").find(attrs)?.groupValues?.get(1) ?: ""
            val bounds = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(attrs)
            val clickable = attrs.contains("""clickable="true"""")
            val className = Regex("""class="([^"]*?)"""").find(attrs)?.groupValues?.get(1)?.substringAfterLast('.') ?: ""

            if ((text.isNotEmpty() || desc.isNotEmpty() || clickable) && bounds != null) {
                val (x1, y1, x2, y2) = bounds.destructured
                val cx = (x1.toInt() + x2.toInt()) / 2
                val cy = (y1.toInt() + y2.toInt()) / 2
                val display = text.ifEmpty { desc }
                val flags = buildString {
                    if (clickable) append("[可点击]")
                    if (attrs.contains("""editable="true""" + "\"")) append("[可编辑]")
                }
                sb.appendLine("$className $flags \"$display\" @[$cx,$cy]")
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else ""
    }

    /** Paste text via clipboard into the currently focused field. */
    private suspend fun pasteViaClipboard(
        a11y: com.phoneagent.device.AgentAccessibilityService,
        text: String,
    ): Boolean {
        // Set clipboard on main thread
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
            } catch (e: Exception) {
                Log.w("AgentEngine", "clipboard set failed", e)
            }
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        kotlinx.coroutines.delay(300)

        // Try accessibility paste
        val root = a11y.rootInActiveWindow
        if (root != null) {
            val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                return true
            }
            // Try inputText as fallback
            if (a11y.inputText(text)) return true
        }
        return false
    }

    private suspend fun executeSchedulerTool(name: String, arguments: Map<String, String>): String {
        return when (name) {
            "scheduler_create" -> {
                val taskName = arguments["name"] ?: return "Error: missing name"
                val command = arguments["command"] ?: return "Error: missing command"
                val repeat = when (arguments["repeat"]) {
                    "daily" -> RepeatMode.DAILY
                    "weekly" -> RepeatMode.WEEKLY
                    "weekdays" -> RepeatMode.WEEKDAYS
                    "interval" -> RepeatMode.INTERVAL
                    else -> RepeatMode.ONCE
                }
                val scheduledTime = arguments["time"]?.let { timeStr ->
                    val parts = timeStr.split(":")
                    if (parts.size == 2) {
                        val cal = java.util.Calendar.getInstance()
                        cal.set(java.util.Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
                        cal.set(java.util.Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
                        cal.set(java.util.Calendar.SECOND, 0)
                        if (cal.timeInMillis <= System.currentTimeMillis()) {
                            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                        }
                        cal.timeInMillis
                    } else 0L
                } ?: 0L
                val interval = arguments["interval_minutes"]?.toLongOrNull() ?: 0
                val id = scheduler.createTask(taskName, command, repeat, scheduledTime, interval)
                "定时任务已创建 (ID: $id): $taskName [$repeat]"
            }
            "scheduler_list" -> {
                val tasks = scheduler.allTasks.first()
                if (tasks.isEmpty()) return "没有定时任务"
                tasks.joinToString("\n") { t ->
                    val status = if (t.enabled) "✓" else "✗"
                    val next = if (t.nextRunTime > 0) {
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(t.nextRunTime))
                    } else "-"
                    "[$status] #${t.id} ${t.name} | ${t.repeatMode} | 下次: $next\n    命令: ${t.command}"
                }
            }
            "scheduler_delete" -> {
                val taskId = arguments["task_id"]?.toLongOrNull() ?: return "Error: invalid task_id"
                scheduler.deleteTask(taskId)
                "定时任务已删除: #$taskId"
            }
            else -> "Unknown scheduler tool: $name"
        }
    }
}
