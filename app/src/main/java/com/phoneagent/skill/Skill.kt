package com.phoneagent.skill

import com.phoneagent.ai.ToolDefinition

/**
 * Base interface for all Skills.
 * A Skill is a pluggable module that provides tools the AI agent can invoke.
 */
interface Skill {
    /** Unique skill identifier. */
    val id: String

    /** Human-readable skill name. */
    val name: String

    /** Skill description. */
    val description: String

    /** List of tools this skill provides. */
    fun getTools(): List<ToolDefinition>

    /** Execute a tool by name with given arguments. Returns result string. */
    suspend fun executeTool(toolName: String, arguments: Map<String, String>): String
}
