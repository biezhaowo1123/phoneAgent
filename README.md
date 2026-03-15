# PhoneAgent

> 一个运行在 Android 手机上的 AI Agent，支持 19 种大模型，通过自然语言控制你的手机。

<p align="center">
  <img src="https://img.shields.io/badge/Android-28%2B-brightgreen?logo=android" />
  <img src="https://img.shields.io/badge/Kotlin-1.9.21-purple?logo=kotlin" />
  <img src="https://img.shields.io/badge/Compose-Material3-blue?logo=jetpackcompose" />
  <img src="https://img.shields.io/badge/License-MIT-yellow" />
</p>

---

## 功能亮点

**🤖 19 种 AI 大模型**
OpenAI · Claude · Gemini · DeepSeek · 通义千问 · 智谱GLM · 月之暗面 · 零一万物 · 百川 · MiniMax · 豆包 · 讯飞星火 · 腾讯混元 · 阶跃星辰 · Groq · Together AI · OpenRouter · Ollama(本地) · 自定义 OpenAI 兼容 API

**📱 设备控制**
4 种控制方式（无障碍服务 / Shell / Shizuku / Root），支持读取屏幕、点击、滑动、输入、截图、Shell 命令、启动/停止应用等

**⚙️ Agent 循环**
AI 自动选择工具并链式调用，最多 30 轮迭代，流式输出 + Markdown 实时渲染

**🧩 Skill 插件**
8 个内置技能（电话短信、应用管理、通知、系统设置、文件管理、剪贴板、即时消息、本地流程执行），可扩展

**⏰ 定时任务**
一次性 / 每天 / 每周 / 工作日 / 间隔 / Cron，基于 WorkManager 持久化调度，开机自动恢复

**💬 对话管理**
多对话切换、上下文记忆（Room 持久化 + 自动摘要）、每对话自定义系统提示词、消息复制/朗读/重新生成/删除

**🖼️ 多模态**
发送图片给 Vision 模型分析，支持 GPT-4V / Claude Vision 等

**🎙️ 语音朗读**
TTS 文字转语音，可调语速，每条消息可点击朗读

**📋 Prompt 模板库**
18+ 精选模板，覆盖写作、编程、翻译、学习、创意等场景，一键应用

---

## 快速开始

### 环境要求

- Android Studio Hedgehog+
- JDK 17
- Android SDK 34（minSdk 28）

### 构建

```bash
./gradlew assembleDebug
```

### 配置

1. 安装到手机，进入 **设置** 页面
2. 选择 AI 提供商，填入 API Key，保存配置
3. 开启 **无障碍服务**（用于设备控制）

---

## 使用示例

| 命令 | 效果 |
|------|------|
| 打开微信 | 启动微信 APP |
| 把音量调到5 | 设置媒体音量 |
| 每天下午6点提醒我下班 | 创建每日定时通知 |
| 读一下屏幕上写了什么 | 无障碍服务读取屏幕内容 |
| 帮我复制一下这段话 | 读取并复制到剪贴板 |
| 在微信给张三发“我到了” | 自动定位联系人并发送消息 |
| 电量还有多少 | 查询电池状态 |
| 30分钟后提醒我开会 | 创建倒计时任务 |

### 即时消息 Skill（微信/QQ）

- 工具名：`skill_im_send_message`
- 参数：
  - `app`: `wechat` 或 `qq`
  - `to`: 联系人名称（建议与通讯录显示名完全一致）
  - `message`: 要发送的内容
  - `mode`: `precise`（最稳）、`balanced`（推荐）、`fast`（最快）
- 示例：
  - `app=wechat,to=张三,message=我到了,mode=balanced`
  - `app=qq,to=李四,message=10分钟后到,mode=fast`

### 本地流程执行 Skill（全手机执行）

- 工具名：`skill_flow_run_workflow`
- 参数：
  - `script_json`: JSON 流程（必填）
  - `mode`: `precise` / `balanced` / `fast`（可选）
  - `timeout_ms`: 全流程超时（可选）
  - `continue_on_error`: 出错是否继续（可选）
- 支持动作（`action`）：
  - `launch_app` / `stop_app`
  - `click_text` / `click_xy`
  - `input_text`
  - `swipe`（方向或坐标）
  - `press`
  - `wait`
  - `assert_contains`
  - `screenshot`
  - `shell`
- 示例：

```json
{
  "defaults": {
    "step_timeout_ms": 6000,
    "retries": 1,
    "retry_delay_ms": 220,
    "continue_on_error": false
  },
  "steps": [
    { "action": "launch_app", "package": "com.android.settings" },
    { "action": "wait", "wait_ms": 500 },
    { "action": "click_text", "text": "搜索" },
    { "action": "input_text", "text": "WLAN" },
    { "action": "press", "key": "enter" },
    { "action": "assert_contains", "expect_text": "WLAN" }
  ]
}
```

---

## 项目结构

```
app/src/main/java/com/phoneagent/
├── ai/                        # AI 服务层
│   ├── AiConfig.kt           #   19 种 Provider 配置与预设
│   ├── AiService.kt          #   OpenAI-compatible API 调用 + 流式 SSE
│   └── ChatMessage.kt        #   消息、工具调用数据类
├── device/                    # 设备控制
│   └── DeviceController.kt   #   无障碍/Shell/Shizuku/Root 四模式控制器
├── engine/                    # Agent 引擎
│   └── AgentEngine.kt        #   核心编排：工具收集 → LLM → 执行 → 循环
├── memory/                    # 对话持久化
│   ├── ConversationManager.kt
│   ├── ConversationDao.kt
│   └── ConversationEntities.kt
├── skill/                     # Skill 插件系统
│   ├── Skill.kt              #   Skill 接口
│   ├── SkillRegistry.kt      #   注册中心
│   └── builtin/              #   7 个内置技能
├── scheduler/                 # 定时任务
│   ├── TaskScheduler.kt      #   调度核心
│   ├── TaskWorker.kt         #   WorkManager Worker
│   └── BootReceiver.kt       #   开机恢复
├── prompt/                    # Prompt 模板
├── voice/                     # TTS 语音朗读
└── ui/                        # Jetpack Compose UI
    ├── PhoneAgentApp.kt       #   导航（对话 / 任务 / 设置）
    └── screens/
        ├── ChatScreen.kt     #   对话界面
        ├── TaskScreen.kt     #   定时任务管理
        ├── SkillScreen.kt    #   技能查看
        ├── PromptScreen.kt   #   Prompt 模板库
        └── SettingsScreen.kt #   设置 + 关于
```

## Agent 执行流程

```
用户: "每天早上8点提醒我喝水"
         │
         ▼
    AgentEngine.chatStream()
         │
         ├─ 收集工具（device_* + scheduler_* + skill_*）
         ├─ 发送给 LLM（含 tool definitions）
         │
         ▼
    LLM 返回 tool_call: scheduler_create
         │  name: "喝水提醒"
         │  command: "发送通知提醒喝水"
         │  repeat: "daily", time: "08:00"
         │
         ▼
    执行工具 → 结果返回 LLM → 生成最终回复
         │
         ▼
    "已创建每日 8:00 喝水提醒 ✓"
```

---

## 扩展开发

### 添加自定义 Skill

```kotlin
class MySkill(private val context: Context) : Skill {
    override val id = "my_skill"
    override val name = "我的技能"
    override val description = "自定义功能"

    override fun getTools() = listOf(
        ToolDefinition(
            name = "my_action",
            description = "执行自定义操作",
            parameters = mapOf(
                "param1" to ToolParameter("string", "参数说明", required = true)
            )
        )
    )

    override suspend fun executeTool(name: String, args: Map<String, String>): String {
        return "执行完成"
    }
}

// 在 PhoneAgentApp.initSkills() 中注册
skillRegistry.register(MySkill(this))
```

---

## 技术栈

| 组件 | 技术 |
|------|------|
| UI | Jetpack Compose 1.6.0 + Material 3 1.2.0 |
| 网络 | Ktor Client 2.3.7 (OkHttp) |
| 序列化 | kotlinx.serialization 1.6.2 |
| 数据库 | Room 2.6.1 |
| 调度 | WorkManager 2.9.0 |
| 设备控制 | Accessibility Service + Shizuku 13.1.5 |
| 图片加载 | Coil 2.5.0 |
| 语言 | Kotlin 1.9.21, JVM 17 |

## 安全说明

- API Key 仅存储在本地设备（DataStore）
- 无障碍服务需用户手动授权
- 敏感操作（电话、短信）通过系统 Intent 确认
- 不收集、不上传任何用户数据

## License

MIT License
