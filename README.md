# Serein

Serein 是一款面向 Android 13 及以上版本的 AI Chat 客户端。项目希望提供高度可定制、流畅克制的外观，以及让日常对话更专注的体验。

## 产品边界

**Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.**

Serein 服务于用户主动发起的对话。功能可以改善聊天、模型选择、对话管理和聊天阅读体验，但不能把产品扩展成自主规划或执行任务的 Agent，也不能扩展成承载项目、文件、工具或协作流程的 Workspace。

## 宗旨

**少即是多。** 每一个功能都应该有存在的理由，每一个动画都应该让交互更舒服。

### 功能取舍

加入功能前，先说清它为谁解决什么聊天问题、在什么场景发生，以及怎样判断它确实有用。功能还必须符合 Chat App 的边界。优先满足最常见的对话流程。无法说明用户价值或验证方式的功能，先不做。

### 动效取舍

动效用于反馈操作、说明状态变化或帮助视线跟随内容。它应短、连贯、不打断阅读，不做仅供装饰的循环效果；同时尊重系统的减少动态效果设置，关闭动效后核心功能仍然完整。

### 自定义取舍

自定义应帮助用户形成舒适、清晰的使用环境，而不是增加维护负担。先建立稳定的基础体验，再根据真实需求扩展可调选项。

## 项目路线图

- MVP 先交付一条可靠、易读、可测试的核心对话流程。
- 后续候选只有在出现明确聊天需求证据、符合产品边界并完成取舍评审后才进入排期。

## Alpha 0.0.5

- 最低支持 Android 13。可在 [GitHub Releases](https://github.com/xiaoycnj/Serein_AI/releases) 下载签名 APK，也可按下方步骤在本地构建调试包。发行包和调试包签名不同，不能互相覆盖安装；卸载会删除本机会话和设置。
- 每轮消息下方可编辑提问、重新生成回复。两种操作都会创建版本，原回答与后续对话可以通过版本按钮切回。
- 在「账户」保存显示名称和简介。资料在本机加密保存，开启「随聊天发送账户资料」后才会放入在线请求的系统提示词。
- 「设置 → 自定义插件」提供两个默认关闭的聊天扩展。自动压缩保留最近四轮和当前提问，将较早的历史分段发给本轮服务商生成摘要，可能产生额外费用。原文仍保存在本机。自动路由在当前服务商已配置的模型中，按提问类型、上下文容量和目录价格选择。实际模型显示在回复旁，手动选模型可退出自动路由。
- 消息支持原生 Markdown 富文本，包括标题、粗体、斜体、引用、列表、代码、链接和表格。长按可选择文本。图片仅显示替代文字，HTML 以文本显示。
- OpenAI 兼容服务商通过 SSE 逐段输出。Markdown 合并刷新，向上翻阅时保留当前滚动位置，回到末尾后恢复跟随。停止生成或连接中断时，已收到的正文会存入聊天记录。回复开头前三个可见字符会短暂模糊渐入，可在「外观 → 减少动效」关闭，也遵循系统动画设置。
- 在「会话 → 设置 → 服务商连接」添加 OpenAI 兼容服务商，填写带 API 路径的 HTTPS 根地址，例如 `https://api.openai.com/v1`。为每一把 Key 命名，在服务商详情指定当前 Key，再拉取模型列表或手动填写模型 ID。从聊天顶部切换模型。
- 模型选择器沿用聊天主题，显示品牌图标、当前模型和服务商。搜索可按名称、模型 ID、发布方或服务商筛选。
- APK 内置 601 个模型 ID 和显式别名，按完整 ID 区分大小写匹配。手动添加和首次拉取时可预填模型名称、发布方与图标。部分已知官方端点会预填思考参数，其他端点沿用服务商默认值。模型设置可手动修改这些字段，重复拉取会保留已有配置。
- 在模型设置中选择适用的思考参数。键盘展开时，输入框上方显示档位滑块。选择「默认」时由服务商决定。请按所用模型的文档选择 `reasoning_effort` 或 DeepSeek 档位。
- 外观包含自定义主题色、纯色与渐变背景、本机图片，以及实色、扁平和磨砂材质。列表、按钮和页面切换使用非线性与弹簧反馈，尊重应用和系统的减少动效设置。
- 升级首次启动时会加密迁移会话。会话和服务商配置使用 Android Keystore 管理的密钥加密，应用备份已禁用，在线请求通过 HTTPS 发送给用户选择的服务商。
- 可用 Android Studio 打开根工程。Windows 上也能用 JDK 17+、Android SDK Platform 36 和 Build-Tools 35.0.0 直接构建。

```powershell
.\scripts\build-alpha.ps1
adb install -r .\outputs\Serein-Alpha-0.0.5.apk
```

用 `./scripts/test-alpha.ps1 -OfflineModel` 走查离线发送、重启恢复、回访历史及设置。脚本会留下两段测试会话。`./scripts/test-alpha.ps1 -ProviderControls` 使用临时服务商和测试 Key 走查多 Key 管理和思考强度控件。

发行打包使用 `./scripts/build-alpha.ps1 -RunChatTests -Release`，输出到 `outputs/release/`。构建需要仓库外的签名库（别名 `serein-release`）；通过 `SEREIN_RELEASE_KEYSTORE` 和 `SEREIN_RELEASE_STORE_PASS` 提供路径和密码。

在离线演示模型发送 `Markdown` 可查看流式富文本示例，无需配置 Key。聊天排版和交互参考 [Material Design 3](https://m3.material.io/styles/typography/overview) 和 [Apple HIG Motion](https://developer.apple.com/design/human-interface-guidelines/motion)。

流式事件处理、Markdown 刷新、滚动跟随与消息版本参考了 [DeepSeek Chat Demo](https://github.com/deepseek-ai/chat-demo/tree/8ad7b56e196b87d8a3215dc09476d6d146ad3f0e)。分段摘要、上下文预算和本地规则路由参考了 [AstraAgent](https://github.com/xiaoycnj/AstraAgent/tree/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f)。`./scripts/build-alpha.ps1 -RunChatTests` 会在打包前运行本机 JVM 回归测试，无需 Android 设备或服务商 Key。

## 开源许可

Serein 的原创代码和资源采用 [MIT 许可证](LICENSE)。第三方资源保留各自的许可证与署名，详见 [随应用打包的许可文件](app/src/main/assets/licenses)。

- 应用通用图标使用 [Lucide v0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0)。
- Markdown 解析使用 [commonmark-java 0.24.0（BSD 2-Clause）](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0)。
- 搜索图标来自 [Tabler Icons v3.41.0（MIT）](https://github.com/tabler/tabler-icons/tree/v3.41.0)，设计资源也可通过 [官方 Figma 插件](https://www.figma.com/community/plugin/1169807996149376642/Tabler-Icons) 获取。
- 模型品牌向量来自 [Lobe Icons（MIT）](https://github.com/lobehub/lobe-icons/tree/9752d967494e48d8f83f2808feb2b0207bf70997)。
- 离线目录从 [LiteLLM 固定快照（MIT）](https://github.com/BerriAI/litellm/blob/ac7674625ceed298d6c411cc964f23aded63989d/model_prices_and_context_window.json) 筛选生成，使用 [AstraAgent 固定目录](https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/models/registry.json) 补充规格与档位。可用 `scripts/update-model-catalog.ps1 -Source <LiteLLM 快照路径> -AstraRegistry <AstraAgent 目录快照路径>` 重建。模型可用性及实际价格由服务商决定。
