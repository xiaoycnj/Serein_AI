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

路线图依据 Android 聊天产品的官方帮助资料及当前 Alpha 的代码与测试状态滚动修订。Gemini Android 支持会话搜索、置顶、重命名和删除。ChatGPT Android 支持会话历史及按消息提供复制、反馈、朗读、分享和重试等操作。Serein 优先实现本机聊天管理、消息编辑和重试。资料库仅为用户主动发起的聊天提供参考资料。[Gemini 聊天管理](https://support.google.com/gemini/answer/13666746?co=GENIE.Platform%3DAndroid&hl=en) · [ChatGPT Android FAQ](https://help.openai.com/en/articles/8142208-chatgpt-ios-and-android-app-faq)

| 阶段 | 目标 | 验收条件 | 取舍 |
| --- | --- | --- | --- |
| Alpha 0.0.6 | 完成核心对话与本地会话管理 | 支持流式回答、停止、Markdown、消息编辑/重试分支；会话置顶/取消置顶、重命名、删除在重启后保持正确 | 不加入云同步 |
| Alpha 0.0.7 | 统一常用控件，验证聊天内交互卡片 | 聊天和设置共用 Material 控件；可离线体验清单；在线卡片可关闭，卡片状态留在本机 | 不加入确认执行、文件或工具编排 |
| Alpha 0.0.8 | 改善中断后的聊天恢复 | 停止和未完成状态随回复版本保存；未收到正文时可手动重试；可复制回复 | 不自动重发，也不覆盖用户的新草稿 |
| Alpha 0.0.9 | 移植 AstraAgent 的 12 项聊天操作 | 本机搜索、归档、消息定位、独立分支、引用和文本导出；[入口与验收](docs/ASTRA-CHAT-PORT.md) | 不执行代码，不接入 Agent 或工作区 |
| Alpha 0.0.10 | 统一常用浮层与容器 | [控件预设](docs/UI-KIT.md)用于聊天与设置，构建脚本要求 APK 小于 64 MB | 使用现有 Material 依赖，保留系统文件选择器 |
| Alpha 0.0.11 | 简化侧栏操作 | 对话左侧内缘右滑打开、侧栏内左滑收起；保留返回键和按钮 | 不占用 Android 系统返回手势区 |
| Alpha 0.0.12 | 让自动模型路由配合聊天选项 | 开启交互卡片时，只自动选择目录标记支持函数调用的模型；手动指定思考强度时使用当前模型 | 路由在本机运行，仅在当前服务商内选择 |
| Alpha 0.0.13 | 提升外观自定义深度 | 设置页实时预览主题、配色、壁纸、字体和圆角；重启后恢复设置 | 壁纸与字体保存在应用私有目录 |
| Alpha 0.0.14 | 在对话中查询网页 | 单条消息可用 DeepSeek Search，并保留可点击来源；也可在浏览器打开 Bing | 每次由用户确认检索词 |
| Alpha 0.0.15 | 在应用内更新和生图 | 关于页检查 GitHub APK 与模型目录；对话中生成、预览和保存图片 | 安装交由 Android 确认；生图由用户逐次提交 |
| Alpha 0.0.16 | 简化搜索与生图 | 普通发送后，由模型按需选择已启用的聊天工具；生图在回复内显示占位和结果 | 默认关闭；每条消息最多执行一次工具，不自动重试 |
| Alpha 0.0.17 | 在对话中使用附件、资料与记忆，独立展示更新进度 | 附件可随消息发送，模型按需检索资料和记忆，资料引用可预览；应用更新页展示下载和校验状态 | 本机加密，资料库检索和主动记忆默认关闭；工具只在当前聊天轮内调用 |
| Alpha 0.0.18 | 修复大表格导致的聊天卡顿 | 流式回复和历史会话均能打开大表格，原始 Markdown 可复制和导出 | 聊天内仅预览部分表格，超出解析上限时显示原文 |
| Alpha 0.0.19 | 在线发送前显示接收方 | 展示聊天、搜索和生图端点，接收方或随附数据范围变化后重新确认 | 可清除已记住的发送确认 |
| Alpha 0.0.20 | 恢复中断的回复 | 在已停止或中断的回复旁点击重试，保留原回复和草稿 | 重试生成一个可切换的版本 |
| Alpha 0.0.21 | 修复流式长回复短暂消失 | 大段文本到达时，立即显示回复并保留原文 | 普通短回复仍有首字渐入，超限时结束模糊动效 |
| Alpha 0.0.22 | 修复模型选择器在旋转时的窗口泄漏 | 覆盖安装后继续使用原有会话和模型配置，横屏、大字号和键盘同时开启时仍可筛选模型 | 搜索、服务商设置和关闭入口同处一行 |
| Alpha 0.0.23 | 修复保存回复和本机配置时的界面卡死 | 加密写入在后台完成，写入失败时保留旧会话并恢复聊天草稿 | 原有加密格式不变，覆盖升级保留本机数据 |
| Alpha 0.0.24 | 缩短长会话打开时的卡顿 | 默认显示最近 32 轮，消息目录、搜索和翻页仍可定位更早消息 | 只限制单次绘制范围，不修改历史、模型上下文或导出 |
| Alpha 0.0.25 | 修复发送后收到密集 Markdown 时界面无响应 | 超长或样式过密的文本使用有界纯文本预览，「消息更多操作 → 导出完整回复」可取回原文 | 普通回复仍按 Markdown 显示，不截断聊天记录或发送上下文 |
| Alpha 后续 | 提升日常对话可靠性与可访问性 | 测试覆盖断流、错误、取消及恢复；系统减少动态效果、屏幕阅读器和窄屏下主要聊天操作可用；服务商配置错误给出可处理反馈 | 优先修复真实使用问题，不扩张到工具编排 |
| Beta 候选 | 改善长历史与数据可控性 | 验证大量会话时的搜索耗时与内存；评估可恢复备份和更细的保留设置 | Markdown 导出只是可阅读副本，不是备份格式 |
| 暂缓 | 多设备账号体系、协作空间、Agent 工具执行、插件市场 | 不适用 | 超出“Not an Agent. Not a Workspace. Just Chat.”边界 |

每项功能进入开发前都要写明目标场景、用户价值、维护成本和可验证条件；动效需改善操作反馈或阅读连续性，并尊重系统减少动态效果。竞品未证实的行为不作为事实或验收依赖。

后续迭代先补齐对话稳定性回归，再打磨聊天界面和可访问性。每轮验收通过后提交并推送 GitHub，核对远端提交。阶段目标、验收门槛和竞品参考见 [迭代路线](docs/ROADMAP.md)。

## Alpha 0.0.25

- 最低支持 Android 13。可在 [GitHub Releases](https://github.com/xiaoycnj/Serein_AI/releases) 下载签名 APK，也可按下方步骤在本地构建调试包。发行包和调试包签名不同，不能互相覆盖安装；卸载会删除本机会话和设置。
- 「设置 → 关于 → 应用更新」检查 GitHub Releases，支持 Alpha 预发行版。点「开始更新」进入动画，进度环随实际收到的字节推进。下载完成和安装前都会验证 SHA-256、包名、版本和当前安装签名。最后点「安装更新」交给 Android 确认。发布标签用 `v0.0.x-alpha`，APK 资产用 `Serein-Alpha-0.0.x.apk`。
- 「热更新模型目录」从 Serein 仓库校验并应用 JSON 模型目录，成功后无需重启。校验失败时使用已有的可用目录。
- 点输入框的回形针添加附件，每条消息最多 3 个。文本、Markdown、CSV、TSV、JSON、XML、YAML、日志和 DOCX 单个限 5 MiB、提取的正文限 60,000 个字符。PNG、JPEG 和 WebP 单个限 2 MiB，最长边限 4096 像素，需选择支持图片输入的模型。PDF 和 OCR 暂未接入。
- 侧栏「资料库」可导入、预览、移除和附加文档。开启「允许模型检索资料库」后，支持函数调用的模型可搜索相关段落并在回复里用 `[F1]` 等标签引用。点回复下方的「资料引用」可查看来源。
- 「设置 → 自定义插件 → 记忆库」可开启主动记忆、查看条目或清空。模型可从本轮用户原话中选择稳定的事实或偏好，回复完成并保存后写入记忆库。后续聊天可按需检索，条目不可手动编辑。
- 资料库和记忆加密保存在本机。主动检索默认关闭，首次向某服务商发送附件或允许检索命中内容时确认发送范围。每轮最多执行 4 次本机工具，或执行一次联网搜索或图片生成。调用本机工具后，本轮会关闭搜索和生图。[AstraAgent 附件](https://github.com/xiaoycnj/AstraAgent/blob/c74966f83ed35275aace67c11abc48fbaea52b32/attachments.py) · [AstraAgent 记忆](https://github.com/xiaoycnj/AstraAgent/blob/c74966f83ed35275aace67c11abc48fbaea52b32/agent_environment.py)
- 首次在线发送会列出可能接收本轮数据的服务商及接口地址。接收方或发送范围变化时重新确认。在「设置 → 相关政策 → 清除发送确认」可清除本机记住的确认记录。
- 在「设置 → 自定义插件」分别开启「模型联网搜索」和「模型生成图片」，然后像平常一样发消息。在线聊天模型须支持函数调用；离线演示不会调用工具。每轮最多执行一次搜索或生图，之后由聊天模型继续回复，不会自动重试工具。
- 搜索只向官方 DeepSeek Search 发送模型填写的检索词，需要官方 DeepSeek 服务商的 API Key；摘要交还聊天模型，来源随回复保存。设置中也可手动打开 Bing 网页，旧的手动搜索记录仍可重新确认检索词。详见 [DeepSeek Search API](https://api-docs.deepseek.com/api/search/)。
- 生图使用「图片服务商」中指定的模型和尺寸，向 OpenAI 兼容的 `/images/generations` 发送模型填写的图像描述。等待时在回复内显示粒子占位，完成后图片弹簧渐入；遵循应用和系统的减少动效设置。搜索和生图都可能单独计费。图片加密保存在会话中，可点开预览并导出原图。参考 [AstraAgent Chat 工具白名单](https://github.com/xiaoycnj/AstraAgent/blob/c74966f83ed35275aace67c11abc48fbaea52b32/runtime_engine.py) 与 [生图动效](https://github.com/xiaoycnj/AstraAgent/blob/c74966f83ed35275aace67c11abc48fbaea52b32/web/assets/css/ui/image-candidates.css)。
- 从对话内容区靠左、避开屏幕最外侧的位置向右滑可打开侧栏；在侧栏内向左滑可收起。「设置 → 外观 → 侧栏滑动手势」可关闭，键盘展开和触摸探索时不接管手势。
- 可选的自动模型路由优先保留完整对话，再按任务档位与目录价格选模型。开启交互卡片后，候选需标记支持函数调用。如果没有合适的候选，继续使用手动选中的模型。手动设置非默认思考强度时也会使用当前模型。
- 每轮消息下方可编辑提问、重新生成回复。两种操作都会创建版本，原回答与后续对话可以通过版本按钮切回。
- 手动停止或在线回复未完成时，已经收到的正文连同状态保存在当前回复版本。重新打开会话后仍可点「重试回复」，用同一条提问生成另一个版本。完全没收到回复时，可以在当前对话点「重试」。重试历史回复时保留输入框里的草稿。
- 「复制回复」将 Markdown 原文与卡片公开文字复制到系统剪贴板，不包含勾选状态。复制确认不显示内容预览；复制后的文字仍可由用户粘贴到其他应用。
- 侧栏可搜标题、提问和回复，也能归档或恢复会话。聊天顶部打开消息目录，向上翻阅时可点「回到最新」。消息更多菜单支持引用到草稿、创建独立分支，并提供代码和表格工具。会话选项可导出当前版本的 Markdown；[12 项移植清单与具体入口](docs/ASTRA-CHAT-PORT.md)。
- 长会话默认显示最近 32 轮。列表首尾可翻到较早或较新的消息，消息目录和历史搜索可直接定位任意已保存的当前分支消息。
- 在侧栏的会话选项里置顶、重命名或删除会话。置顶状态加密保存在本机，新消息不会让置顶会话移出顶部；重命名保留消息和版本。确认删除后，本机记录及其全部版本会被移除。
- 在「账户」保存显示名称和简介。资料在本机加密保存，开启「随聊天发送账户资料」后才会放入在线请求的系统提示词。
- 「设置 → 自定义插件」的聊天扩展默认关闭。自动压缩保留最近四轮和当前提问，将较早的历史分段发给本轮服务商生成摘要，可能产生额外费用。原文仍保存在本机。自动路由在当前服务商已配置的模型中，按提问类型、上下文容量和目录价格选择。实际模型显示在回复旁，手动选模型可退出自动路由。
- 同处可开启在线「交互卡片」，需要模型及接口支持函数调用。移植了 [AstraAgent 的 IUI](https://github.com/xiaoycnj/AstraAgent/blob/main/docs/INTELLIGENT-UI.md) 子集：说明、指标、对比、清单和单选。不支持表单、图表或执行确认。卡片不加载网页、脚本或网络资源；选择按消息版本加密保存，不会自动发送。「加入草稿」保留原有输入，聊天仍由用户手动发送。在离线演示点击「试试交互清单」可以直接体验。
- 消息支持原生 Markdown 富文本，包括标题、粗体、斜体、引用、列表、代码、链接和表格。聊天内每张表格预览最多 12 行、8 列，包含表头。超出解析上限时保留表格原文。长按可选择文本。图片仅显示替代文字，HTML 以文本显示。
- SSE 按协议读取正文和结束状态。Markdown 刷新与屏幕帧对齐，向上翻阅时保留当前滚动位置，回到末尾后恢复跟随。停止生成或连接中断时，已收到的正文会存入聊天记录。回复开头前三个可见字符有短暂的模糊渐入，可在「外观 → 减少动效」关闭，也遵循系统动画设置。
- 在「会话 → 设置 → 服务商连接」填写带 API 路径的 HTTPS 根地址。OpenAI、DeepSeek 和兼容网关沿用 Chat Completions。填写 `https://api.anthropic.com/v1` 会使用官方 Anthropic Messages API，思考强度由模型决定。配置 Key 后可拉取模型列表或手动填写模型 ID，从聊天顶部切换模型。
- 模型选择器沿用聊天主题，显示品牌图标、当前模型和服务商。搜索可按名称、模型 ID、发布方或服务商筛选。
- APK 内置 601 个模型 ID 和显式别名，按完整 ID 区分大小写匹配。手动添加和首次拉取时可预填模型名称、发布方与图标。部分已知官方端点会预填思考参数，其他端点沿用服务商默认值。模型设置可手动修改这些字段，重复拉取会保留已有配置。
- 在模型设置中选择适用的思考参数。键盘展开时，输入框上方显示档位滑块。选择「默认」时由服务商决定。请按所用模型的文档选择 `reasoning_effort` 或 DeepSeek 档位。
- 「设置 → 外观」可选雨松、星雾、暖纸、海雾四个预设，每款都支持浅色、深色和跟随系统。可分别设置界面底色、强调色、消息气泡、输入框和聊天正文颜色。
- 本机壁纸支持铺满、完整显示、不透明度和模糊。磨砂材质支持调整表面不透明度，正文和控件在低对比时使用可读配色或实色底。
- 正文字号、消息与输入框圆角独立调节。可使用系统、衬线、等宽字体，或导入 12 MiB 以内的 TTF/OTF。设置页预览随调整更新，支持恢复默认外观。
- 列表、按钮和页面切换使用非线性与弹簧反馈，尊重应用和系统的减少动效设置。
- 升级首次启动时会加密迁移会话。会话和服务商配置使用 Android Keystore 管理的密钥加密，应用备份已禁用，在线请求通过 HTTPS 发送给用户选择的服务商。
- 可用 Android Studio 打开根工程。Windows 脚本使用同一套 Gradle Wrapper（8.13），需要 JDK 17+、Android SDK Platform 36 和 Build-Tools 35.0.0。首次构建需下载 Gradle 和依赖。

```powershell
.\scripts\build-alpha.ps1
adb install -r .\outputs\Serein-Alpha-0.0.25.apk
```

用 `./scripts/test-alpha.ps1 -OfflineModel` 走查离线发送、重启恢复、回访历史及设置。脚本会留下两段测试会话。`./scripts/test-alpha.ps1 -ProviderControls` 使用临时服务商和测试 Key 走查多 Key 管理和思考强度控件。

发行打包使用 `./scripts/build-alpha.ps1 -RunChatTests -Release`，输出到 `outputs/release/`。构建需要仓库外的签名库（别名 `serein-release`）；通过 `SEREIN_RELEASE_KEYSTORE` 和 `SEREIN_RELEASE_STORE_PASS` 提供路径和密码。

在离线演示模型发送 `Markdown` 可查看流式富文本示例，无需配置 Key。可在正文出现后点停止，重开会话确认部分回复和停止标记仍在。聊天排版和交互参考 [Material Design 3](https://m3.material.io/styles/typography/overview) 和 [Apple HIG Motion](https://developer.apple.com/design/human-interface-guidelines/motion)。

流式处理对照了 [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion) 和 [Anthropic Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)。帧刷新和渐入参考了 [AstraAgent 的会话渲染](https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/conversation.js) 与 [动效样式](https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/css/ui/chat-motion.css)。消息版本和滚动跟随参考了 [DeepSeek Chat Demo](https://github.com/deepseek-ai/chat-demo/tree/8ad7b56e196b87d8a3215dc09476d6d146ad3f0e)，分段摘要和本地模型路由参考了 [AstraAgent](https://github.com/xiaoycnj/AstraAgent/tree/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f)。

`./scripts/build-alpha.ps1 -RunChatTests` 会在打包前运行本机 JVM 回归测试，无需 Android 设备或服务商 Key。

## 开源许可

Serein 的原创代码和资源采用 [MIT 许可证](LICENSE)。第三方资源保留各自的许可证与署名，详见 [随应用打包的许可文件](app/src/main/assets/licenses)。

- 应用通用图标使用 [Lucide v0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0)。
- 侧栏入口采用 [Heroicons v2.2.0（MIT）](https://github.com/tailwindlabs/heroicons/tree/v2.2.0)，手势开关使用 [SVG Repo 的 Swipe Right Gesture（CC0）](https://www.svgrepo.com/svg/325667/swipe-right-gesture)。仅随包分发两个矢量，无运行时依赖。
- 本地路由的候选能力筛选与档位排序参考 [AutoModelRouter（CC0 1.0）](https://github.com/xiaoycnj/AutoModelRouter/tree/9ba754d02e08bc1069b0f629e2cbcd5981100500)，由应用内的 Java 代码执行。
- 主题预设、壁纸与排版设置参考 AstraAgent 的 [主题风格](https://github.com/xiaoycnj/AstraAgent/blob/c74966f83ed35275aace67c11abc48fbaea52b32/web/assets/js/features/theme-styles.js) 和 [个性化设置](https://github.com/xiaoycnj/AstraAgent/blob/c74966f83ed35275aace67c11abc48fbaea52b32/web/assets/js/features/personalization.js)，由 Android 原生界面渲染。
- Markdown 解析使用 [commonmark-java 0.33.0（BSD 2-Clause）](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.33.0)。
- 通用控件使用 [Material Components for Android 1.13.0（Apache 2.0）](https://github.com/material-components/material-components-android/releases/tag/1.13.0)。
- 搜索图标来自 [Tabler Icons v3.41.0（MIT）](https://github.com/tabler/tabler-icons/tree/v3.41.0)，设计资源也可通过 [官方 Figma 插件](https://www.figma.com/community/plugin/1169807996149376642/Tabler-Icons) 获取。
- 模型品牌向量来自 [Lobe Icons（MIT）](https://github.com/lobehub/lobe-icons/tree/9752d967494e48d8f83f2808feb2b0207bf70997)。
- 离线目录从 [LiteLLM 固定快照（MIT）](https://github.com/BerriAI/litellm/blob/ac7674625ceed298d6c411cc964f23aded63989d/model_prices_and_context_window.json) 筛选生成，使用 [AstraAgent 固定目录](https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/models/registry.json) 补充规格与档位。可用 `scripts/update-model-catalog.ps1 -Source <LiteLLM 快照路径> -AstraRegistry <AstraAgent 目录快照路径>` 重建。模型可用性及实际价格由服务商决定。
