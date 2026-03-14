package com.phoneagent.skill

import com.phoneagent.ai.ToolDefinition

/**
 * Registry that manages all available Skills.
 * Skills register their tools, and the agent can discover and invoke them.
 */
class SkillRegistry {

    private val skills = mutableMapOf<String, Skill>()

    fun register(skill: Skill) {
        skills[skill.id] = skill
    }

    fun unregister(skillId: String) {
        skills.remove(skillId)
    }

    fun getSkill(skillId: String): Skill? = skills[skillId]

    fun getAllSkills(): List<Skill> = skills.values.toList()

    /** Get all tools from all registered skills, prefixed with skill_ */
    fun getAllTools(): List<ToolDefinition> {
        return skills.values.flatMap { skill ->
            skill.getTools().map { tool ->
                tool.copy(name = "skill_${skill.id}_${tool.name}")
            }
        }
    }

    /** Execute a tool. Tool name format: skill_{skillId}_{toolName} */
    suspend fun executeTool(fullToolName: String, arguments: Map<String, String>): String {
        // Parse: skill_{skillId}_{toolName}
        val parts = fullToolName.removePrefix("skill_").split("_", limit = 2)
        if (parts.size < 2) return "Error: Invalid tool name format: $fullToolName"

        val skillId = parts[0]
        val toolName = parts[1]

        val skill = skills[skillId]
            ?: return "Error: Skill '$skillId' not found"

        return try {
            skill.executeTool(toolName, arguments)
        } catch (e: Exception) {
            "Error executing $fullToolName: ${e.message}"
        }
    }
}
