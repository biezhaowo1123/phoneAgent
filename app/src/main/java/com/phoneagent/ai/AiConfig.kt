package com.phoneagent.ai

import kotlinx.serialization.Serializable

/** AI provider configuration */
@Serializable
data class AiConfig(
    val provider: AiProvider = AiProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o",
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """你是PhoneAgent——一个完全运行在安卓设备上的个人AI助手。

【最重要的规则】
你必须通过调用工具(function call)来执行操作。绝对不要只用文字描述你要做什么。
错误示范："我来点击搜索图标" ← 这只是文字，什么都不会发生
正确做法：直接调用 device_click_xy 工具 ← 这才会真正执行点击
每一步操作都必须调用对应的工具。不要描述计划，直接执行。

你的核心能力：
1. 设备控制：打开APP、点击、滑动、输入、截屏、Shell命令（device_*工具）
2. 定时任务：创建/管理定时任务（scheduler_*工具）
3. 系统功能：文件管理、剪贴板、通知、系统设置（skill_*工具）

操作APP的步骤：
1. 先用 device_read_screen 查看屏幕内容和坐标
2. 根据返回的元素信息，用 device_click_xy 点击坐标或 device_click_text 点击文字
3. 用 device_input_text 输入文字。如果失败，先点击输入框获取焦点再重试
4. 操作后再次 device_read_screen 确认结果，然后决定下一步
5. 如果屏幕内容读取不到（某些APP不支持），用坐标直接操作

关键参数：
- 屏幕尺寸1080x2340，状态栏约100px高
- 可用 device_shell 执行任意Shell命令

微信操作坐标参考（1080x2340屏幕）：
- 搜索图标: (870, 150)
- 搜索输入框: (540, 130)
- 第一个搜索结果: (540, 350)
- 底部消息输入框: (450, 2200)
- 发送按钮: (990, 2200)"""

        /** Preset configurations for all major providers. */
        val PRESETS: Map<AiProvider, ProviderPreset> = mapOf(
            AiProvider.OPENAI to ProviderPreset("https://api.openai.com/v1", "gpt-4o", "Bearer"),
            AiProvider.CLAUDE to ProviderPreset("https://api.anthropic.com/v1", "claude-sonnet-4-20250514", "x-api-key"),
            AiProvider.GEMINI to ProviderPreset("https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", "Bearer"),
            AiProvider.DEEPSEEK to ProviderPreset("https://api.deepseek.com/v1", "deepseek-chat", "Bearer"),
            AiProvider.QWEN to ProviderPreset("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max", "Bearer"),
            AiProvider.ZHIPU to ProviderPreset("https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "Bearer"),
            AiProvider.MOONSHOT to ProviderPreset("https://api.moonshot.cn/v1", "moonshot-v1-128k", "Bearer"),
            AiProvider.YI to ProviderPreset("https://api.lingyiwanwu.com/v1", "yi-large", "Bearer"),
            AiProvider.BAICHUAN to ProviderPreset("https://api.baichuan-ai.com/v1", "Baichuan4", "Bearer"),
            AiProvider.MINIMAX to ProviderPreset("https://api.minimax.chat/v1", "MiniMax-Text-01", "Bearer"),
            AiProvider.DOUBAO to ProviderPreset("https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-256k", "Bearer"),
            AiProvider.SPARK to ProviderPreset("https://spark-api-open.xf-yun.com/v1", "generalv3.5", "Bearer"),
            AiProvider.HUNYUAN to ProviderPreset("https://hunyuan.tencentcloudapi.com/v1", "hunyuan-pro", "Bearer"),
            AiProvider.STEPFUN to ProviderPreset("https://api.stepfun.com/v1", "step-2-16k", "Bearer"),
            AiProvider.OLLAMA to ProviderPreset("http://localhost:11434/v1", "llama3", "Bearer"),
            AiProvider.GROQ to ProviderPreset("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "Bearer"),
            AiProvider.TOGETHER to ProviderPreset("https://api.together.xyz/v1", "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo", "Bearer"),
            AiProvider.OPENROUTER to ProviderPreset("https://openrouter.ai/api/v1", "openai/gpt-4o", "Bearer"),
            AiProvider.CUSTOM to ProviderPreset("", "", "Bearer"),
        )
    }
}

@Serializable
data class ProviderPreset(
    val baseUrl: String,
    val defaultModel: String,
    val authType: String, // "Bearer" or "x-api-key"
)

@Serializable
enum class AiProvider(val displayName: String) {
    OPENAI("OpenAI"),
    CLAUDE("Claude"),
    GEMINI("Gemini"),
    DEEPSEEK("DeepSeek"),
    QWEN("通义千问"),
    ZHIPU("智谱GLM"),
    MOONSHOT("月之暗面"),
    YI("零一万物"),
    BAICHUAN("百川"),
    MINIMAX("MiniMax"),
    DOUBAO("豆包"),
    SPARK("讯飞星火"),
    HUNYUAN("腾讯混元"),
    STEPFUN("阶跃星辰"),
    OLLAMA("Ollama(本地)"),
    GROQ("Groq"),
    TOGETHER("Together AI"),
    OPENROUTER("OpenRouter"),
    CUSTOM("自定义"),
}
