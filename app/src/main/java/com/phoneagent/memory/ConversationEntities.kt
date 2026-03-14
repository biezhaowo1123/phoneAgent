package com.phoneagent.memory

import androidx.room.*
import kotlinx.serialization.Serializable

/** A conversation session with its metadata. */
@Entity(tableName = "conversations")
@Serializable
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    /** Summary of the conversation for context carry-over. */
    val summary: String = "",
    val archived: Boolean = false,
    /** Custom system prompt for this conversation. Empty = use global default. */
    val systemPrompt: String = "",
)

/** A single message within a conversation. */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")]
)
@Serializable
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,        // user, assistant, tool, system
    val content: String,
    val toolCallsJson: String? = null, // serialized tool calls
    val timestamp: Long = System.currentTimeMillis(),
)
