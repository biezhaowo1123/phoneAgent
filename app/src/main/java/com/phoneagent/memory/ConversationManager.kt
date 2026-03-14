@file:OptIn(kotlinx.serialization.InternalSerializationApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package com.phoneagent.memory

import android.content.Context
import com.phoneagent.ai.ChatMessage
import com.phoneagent.ai.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * ConversationManager: handles conversation persistence and context memory.
 *
 * Features:
 * - Create/switch/delete conversations
 * - Auto-save messages to Room database
 * - Load conversation history on switch
 * - Generate conversation summaries for context carry-over
 * - Limit context window while preserving memory
 */
class ConversationManager(context: Context) {

    private val db = ConversationDatabase.getInstance(context)
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Current active conversation ID. */
    var currentConversationId: Long = -1L
        private set

    /** All active (non-archived) conversations. */
    val conversations: Flow<List<Conversation>> = conversationDao.getActiveConversations()

    /** Messages for the current conversation as a Flow. */
    fun currentMessages(): Flow<List<ChatMessage>> {
        return messageDao.getMessages(currentConversationId).map { entities ->
            entities.map { it.toChatMessage() }
        }
    }

    /** Create a new conversation and set it as current. */
    suspend fun createConversation(title: String = "新对话", systemPrompt: String = ""): Long {
        val conversation = Conversation(title = title, systemPrompt = systemPrompt)
        val id = conversationDao.insertConversation(conversation)
        currentConversationId = id
        return id
    }

    /** Switch to an existing conversation. Returns its messages. */
    suspend fun switchConversation(conversationId: Long): List<ChatMessage> {
        currentConversationId = conversationId
        val messages = messageDao.getMessagesList(conversationId)
        return messages.map { it.toChatMessage() }
    }

    /** Save a message to the current conversation. */
    suspend fun saveMessage(message: ChatMessage) {
        if (currentConversationId <= 0) {
            createConversation()
        }

        val entity = MessageEntity(
            conversationId = currentConversationId,
            role = message.role,
            content = message.content,
            toolCallsJson = message.toolCalls?.let {
                json.encodeToString(it)
            },
            timestamp = message.timestamp,
        )
        messageDao.insertMessage(entity)
        conversationDao.incrementMessageCount(currentConversationId)

        // Auto-generate title from first user message
        val conv = conversationDao.getConversationById(currentConversationId)
        if (conv != null && conv.title == "新对话" && message.role == "user") {
            val title = message.content.take(30).let {
                if (message.content.length > 30) "$it..." else it
            }
            conversationDao.updateTitle(currentConversationId, title)
        }
    }

    /** Delete a conversation and all its messages. */
    suspend fun deleteConversation(conversationId: Long) {
        messageDao.deleteMessagesForConversation(conversationId)
        conversationDao.deleteConversation(conversationId)
        if (currentConversationId == conversationId) {
            currentConversationId = -1
        }
    }

    /** Get recent messages for context window (limited to avoid token overflow). */
    suspend fun getContextMessages(maxMessages: Int = 50): List<ChatMessage> {
        if (currentConversationId <= 0) return emptyList()

        val conv = conversationDao.getConversationById(currentConversationId)
        val recentMessages = messageDao.getRecentMessages(currentConversationId, maxMessages)
            .reversed() // oldest first
            .map { it.toChatMessage() }

        // If conversation has a summary and we're truncating, prepend summary as context
        val totalCount = messageDao.getMessageCount(currentConversationId)
        if (totalCount > maxMessages && conv?.summary?.isNotEmpty() == true) {
            val summaryMessage = ChatMessage(
                role = "system",
                content = "[之前的对话摘要]\n${conv.summary}"
            )
            return listOf(summaryMessage) + recentMessages
        }

        return recentMessages
    }

    /** Update the conversation summary (called periodically or when context is too long). */
    suspend fun updateSummary(summary: String) {
        if (currentConversationId > 0) {
            conversationDao.updateSummary(currentConversationId, summary)
        }
    }

    /** Get the current conversation's summary. */
    suspend fun getCurrentSummary(): String {
        if (currentConversationId <= 0) return ""
        return conversationDao.getConversationById(currentConversationId)?.summary ?: ""
    }

    /** Rename a conversation. */
    suspend fun renameConversation(conversationId: Long, newTitle: String) {
        conversationDao.updateTitle(conversationId, newTitle)
    }

    /** Update the system prompt for the current conversation. */
    suspend fun updateSystemPrompt(prompt: String) {
        if (currentConversationId > 0) {
            conversationDao.updateSystemPrompt(currentConversationId, prompt)
        }
    }

    /** Get the system prompt for the current conversation. */
    suspend fun getCurrentSystemPrompt(): String {
        if (currentConversationId <= 0) return ""
        return conversationDao.getConversationById(currentConversationId)?.systemPrompt ?: ""
    }

    // ---------- Conversion ----------

    private fun MessageEntity.toChatMessage(): ChatMessage {
        val toolCalls = toolCallsJson?.let {
            try {
                json.decodeFromString<List<ToolCall>>(it)
            } catch (_: Exception) {
                null
            }
        }
        return ChatMessage(
            role = role,
            content = content,
            toolCalls = toolCalls,
            timestamp = timestamp,
        )
    }
}
