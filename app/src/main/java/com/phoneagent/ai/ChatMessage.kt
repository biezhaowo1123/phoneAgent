package com.phoneagent.ai

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    /** Base64-encoded images or URLs for vision/multimodal chat. */
    val images: List<String>? = null,
    /** URL of a generated image (DALL-E etc). */
    val imageUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class ToolCall(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter> = emptyMap(),
)

@Serializable
data class ToolParameter(
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enum: List<String>? = null,
)

/** Events emitted during streaming AI response. */
sealed class StreamEvent {
    /** Incremental text token from the AI. */
    data class TextDelta(val text: String) : StreamEvent()
    /** The complete assembled message when streaming finishes. */
    data class Complete(val message: ChatMessage) : StreamEvent()
}
