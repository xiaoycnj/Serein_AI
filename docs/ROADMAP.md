# Serein 迭代路线

少即是多。Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.

先保证每次消息能发出、能恢复、能找回，再打磨聊天界面。闪退、界面无响应、丢消息、重复发送和隐私回归阻断当轮交付。每轮独立验证后推送 GitHub，发行包维持 Android 13+、原发行签名和小于 64 MB 的限制。

## 接下来的迭代

按验收结果推进，不用固定日期代替验收。每轮只选一个主要聊天场景；出现阻断问题时，暂停外观迭代，先修复并复测。

| 顺序 | 状态 | 本轮交付 | 验收 |
| --- | --- | --- | --- |
| 0.0.25 · 发送稳定性 | 签名预发行版已发布 | 密集 Markdown 回复用纯文本预览，历史、复制和导出保留原文 | Android 13 重放已复现卡死的 18,000 段回复，确认无新增应用崩溃或 ANR，能操作、冷启动回读和完整导出；Android 17 用在线测试服务商连续发送并验证恢复 |
| 发送回归基线 | 已验收 | 用[独立测试包](../scripts/test-chat-regression.ps1)重放发送、断流、停止、重试与恢复 | Android 13、17 模拟器均通过：核对每台 8 个预期请求、取消时零请求、断流和重试保留版本与独立草稿、冷启动回读，无新增 Serein 崩溃或 ANR。在线服务商冒烟测试单独记录 |
| 聊天界面 | 已验收 | 对齐消息与输入区，补齐等待、生成和保存提示，将断流原因与重试放在同一区域 | 对照 `1e8812c` 的截图，覆盖 Android 13 浅色与 Android 17 深色、320 dp 宽、1.45 倍字号、动画关闭；两台模拟器均重跑完整发送回归 |
| 导航与无障碍 | 已验证主要路径 | 保留长表单的保存/取消栏，改进窄屏标题、横屏侧栏、版本按钮排布及读屏与键盘焦点恢复 | Android 13、17 模拟器验证模型表单在 IME 开启时保存、横屏导航、连续 Tab 和关闭后焦点恢复；另验证应用侧滑、实际启用的系统边缘返回及 TalkBack 焦点显示。Google Play TalkBack 逐项播报尚未验收 |
| Alpha 0.0.26 | 签名构建及覆盖升级通过，待发布 | 打开模型选择器后将初始键盘焦点交给搜索框；保留模型面板的系统截图保护 | Android 13、17 隔离回归矩阵及长历史检查通过；`:app:chatChecks` 通过。两台模拟器均从公开的 0.0.25 覆盖升级到同一份 0.0.26 签名 APK |
| 下一轮 · Beta 候选 | 验收中 | 汇总稳定性、长历史、覆盖升级和可访问性证据，评估是否达到候选版门槛 | 对同一最终候选复核 1,200 轮历史和导出；补齐 Google Play 版 TalkBack 逐项播报及剩余读屏路径。Markdown 文件仅用于阅读和归档 |

## 每轮交付规则

1. 选一个用户场景，写明当前行为、预期结果和复现方法。优先沿用已有控件和设计语言；新功能与动效必须有可说明的聊天价值。
2. 跑 `:app:chatChecks` 和受影响的模拟器回归。改 UI 时保留前后对照；涉及安装时核对 APK 版本、签名和大小。用合成消息验证，不将 Key、聊天数据或原始设备日志提交到仓库。
3. 记录已通过、未覆盖和已知限制，提交当前轮的相关改动，推送 `xiaoycnj/Serein_AI` 后核对远端 SHA。推送源码不等于发布 APK；发应用内更新时，另验 GitHub 发行资产的版本、签名和摘要。

参考产品时，记录来源、观察日期、交互证据和 Serein 的取舍。不从截图推断内部实现或性能，也不复制专有代码、品牌素材和整套界面。复用开源代码或素材前，按具体版本核对许可证、保留署名及必要声明；优先选 MIT、CC0 或其他可兼容且义务清楚的资源。没有授权的实现只研究可见行为，由 Serein 独立实现。

聊天界面轮参考 [Apple HIG 的排版层级](https://developer.apple.com/design/human-interface-guidelines/typography)和 [Android Views 无障碍指南](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)，保留原有字体、主题偏好和 48 dp 图标触控区。对照截图用合成回复采集，保存在本地 `outputs/chat-regression/`；长模型名顶栏省略和模型配置表单不在本轮改动范围内。

导航轮使用 `test-chat-regression.ps1 -Navigation -CaptureUi` 验证。Android 17 的深色测试宽度为 320 dp，系统字号 1.8 倍；横屏实测可滚动侧栏并打开设置。旧轮 Android 13 的边缘返回检查未生效；2026-11-24 在 API 33 和 API 37 启用手势模式、确认 SystemUI Back handler 启用后，重新通过真实左边缘返回和应用侧滑。另在 Android 17 的隔离包使用 [TalkBack FOSS 13.0-f02](https://github.com/talkback-foss-team/talkback-foss/releases/tag/v13.0.0.473912301-f02) 确认侧栏开启时、关闭侧栏和关闭模型选择器后的读屏焦点，没有把它当成 Google Play 版逐项朗读验收。测试后卸载读屏和隔离包，恢复模拟器设置。

### 2026-11-24 · Alpha 0.0.26 源码验收

- 回归命令：`./scripts/test-chat-regression.ps1 -Serial @('emulator-5584','emulator-5586') -Navigation -RequireEdgeBack -LongHistory`。收据位于 `outputs/chat-regression/f90a4aaa0965451e940c8fabf2fb8167/`；Android 13（API 33）和 Android 17（API 37）均完成。每台设备的精确请求序列为 8 个；发送、取消、断流、停止、重试、版本与草稿保存、冷启动恢复均通过，未观察到新增 Serein 崩溃或 ANR。
- 两台设备均将系统导航设为手势模式，并检查 SystemUI Back 手势处理器处于启用且允许状态；真实左边缘返回和应用侧滑均通过。1,200 轮合成长历史完成旧格式到加密格式迁移、首尾定位、搜索、分页、冷启动回读；Markdown 导出逐条包含 1,200 个提问和回复，顺序一致，文件为 272,790 字节。
- 截图导航复测命令：`./scripts/test-chat-regression.ps1 -Serial @('emulator-5584','emulator-5586') -Navigation -RequireEdgeBack -CaptureUi`。收据位于 `outputs/chat-regression/034e8b2c42d84c40a13539870db9b8a2/`；两台设备均各保存 8 张非 secure 页面截图、发出 8 个预期请求且未新增崩溃或 ANR。模型选择器截图因窗口启用 secure 标记而跳过，搜索、IME、筛选、返回焦点和横屏层级断言照常运行。
- `:app:chatChecks` 通过全部 14 组检查；两个 PowerShell 回归脚本通过语法解析。构建日志有“Platform-Tools licence not accepted”警告，但目标任务最终为 `BUILD SUCCESSFUL`。这不是 SDK 安装或接受许可的验收。
- 模型选择器搜索框现于打开时请求键盘焦点。API 33、37 层级断言已验证输入、筛选与关闭后焦点恢复；模型选择器标记为 secure，因此 `-CaptureUi` 不再对该面板调用系统截图，但不跳过交互断言。现有已签名 0.0.26 APK 早于此源码改动，不能作为本轮修改的安装包证据。
- Google Play API 33 镜像预装 TalkBack 14.2.0.618048417，服务已绑定且触摸探索开启。已采集会话顶栏、侧栏入口与关闭、搜索项及模型面板的焦点显示截图。逐项语音播报、模型选择器 secure 窗口截图及最新源码上的完整读屏操作仍未验证；不以这些截图替代播报验收。

### 2026-11-25 · Alpha 0.0.26 签名构建与升级验收

- 从 `79e0a46e7e18e67e1267d6e7d27a6239fb9d2a49` 运行 `./scripts/build-alpha.ps1 -Release -RunChatTests`。14 组检查通过，签名 APK 生成于 `outputs/release/Serein-Alpha-0.0.26.apk`。包名为 `app.serein.chat`，最低 API 为 33，版本名为 `0.0.26-alpha`，版本码为 26，大小为 11,751,609 字节。APK 的 SHA-256 为 `86f0bb7839e6c1376e1bd0797054cd4491a6889886cb1a33c496f30d20e585d6`，证书 SHA-256 为 `8b5084d9f82a15eb059adb277671b8ec8cfbb1897d0eeddbc40fba81a70a8894`，与 0.0.25 一致。
- 将公开发行的 `Serein-Alpha-0.0.25.apk` 放入 `outputs/release/`，核对下方历史记录中的摘要，并运行 `./scripts/test-signed-upgrade.ps1 -Serial @('emulator-5584','emulator-5586') -Baseline ./outputs/release/Serein-Alpha-0.0.25.apk -Candidate ./outputs/release/Serein-Alpha-0.0.26.apk`。Android 13、17 均保留原安装标识、两轮聊天、服务商、模型和 Key 条目，新增离线回复在冷启动后仍可读，测试窗口内无新增 Serein 崩溃或 ANR。此路径覆盖 Key 条目的持久化，使用升级后的 Key 向服务商发请求仍待验证。
- 在 Google Play API 33 模拟器安装相同摘要的签名 APK。TalkBack `14.2.0.618048417` 开启触摸探索，侧栏打开后截图记录到「关闭侧栏」按钮取得无障碍焦点；设备日志同时记录到 TalkBack 请求音频焦点和 TTS 合成。逐项标签、实际播报内容以及关闭侧栏或模型面板后的读屏焦点仍待完整核对。
- 1,200 轮历史用例通过 `run-as` 为 `.chatqa` 注入合成记录。发行包需通过聊天流程生成测试历史，再复核翻页、搜索、重启和导出。0.0.26 发行资产等待候选验收。

## 历次改进

| 顺序 | 用户场景 | 本轮交付 | 验收 |
| --- | --- | --- | --- |
| 热修 · 0.0.18 | 一条大表格回复让聊天失去响应 | 限制表格解析和预览规模，保留 Markdown 原文 | 从上版覆盖安装，回访历史。模拟器走普通流、断流、主动停止及大表格 |
| 1 · 安全 · 0.0.19 | 同一条聊天可能发送给多个服务商 | 发送前展示接收方和数据范围，改动后重新确认，允许清除已记住的确认 | 取消不发请求，批准才发送。验证更换接收方、清除确认与重启 |
| 2 · 功能 · 0.0.20 | 断流后希望再试一次，或者先保留已收到的正文 | 在中断回复旁提供明确的重试操作，保持原分支和草稿 | 断流、主动停止、重启、切换版本及重试失败均可回到已有内容 |
| 热修 · 0.0.21 | 大段流式回复在首字动效期间短暂消失 | 限制模糊动效的绘制范围，完整保留回复 | Android 13 覆盖安装。验证 3,200 行回复、历史回访、Markdown 导出及生成中切换前后台 |
| 3 · 外观 · 0.0.22 | 在小屏和键盘打开时找到合适模型 | 在同一行显示搜索、服务商设置与关闭，旋转时释放选择器窗口 | 检查浅色、深色、1.8 倍字号、窄屏、横屏、键盘和减少动效 |
| 4 · 稳定 · 0.0.23 | 回复结束或切换模型时遇到慢写入 | 将会话和服务商配置的序列化、加密及写盘移到后台，成功后才更新本机记录 | Android 13、17 模拟器从 0.0.22 覆盖安装；Android 13 流式 3,200 行完整导出、写盘阻塞 9.78 秒时侧栏仍响应、旋转无重复、写入失败恢复草稿 |
| 5 · 稳定 · 0.0.24 | 回访累积了大量消息的会话，快速滚动和定位 | 以 32 轮为窗口按需绘制，消息目录与历史搜索保持完整 | Android 13 用同一份加密的 1,200 轮 Markdown 测试记录，验证首尾定位、前后翻页和完整导出 |

0.0.25 验证记录：旧版在 Android 13 模拟器上渲染密集 Markdown 时触发输入超时（ANR），并未捕获 Serein 进程闪退。修复版通过普通发送、断流保留、18,000 段完整导出和冷启动回读；Android 17 的 KenAPI 隔离包通过连续发送、切后台返回和重启回读。用户真机的问题是否同源仍待日志确认。

[Alpha 0.0.25 预发行版](https://github.com/xiaoycnj/Serein_AI/releases/tag/v0.0.25-alpha)指向 `0f2efde4c4f74894fa37d3d32405bac3296a262e`。GitHub 记录的 APK 大小为 11,745,733 字节，SHA-256 为 `9111e9d6a3e430a832613d23599882290b8adf21c01e22442524fac493b64b2e`，与本地验签文件一致。发行证书与 0.0.24 相同。用标签提交重建并对照 APK，应用代码、资源及清单逐项一致，文本许可证差异仅为换行，构建工具写入的 Git 元数据也有差异。

## 五款 Android 应用对照

2026-11-23 对照 Google Play 商店图片。图片用于理解布局，功能以官方文档或公开演示补证。公开 API 协议与演示独立列出。前三家商店客户端的内部实现未核验。

| 产品 | 商店图片中的取舍 | 功能证据 | 公开实现或协议 | Serein 的取舍 |
| --- | --- | --- | --- | --- |
| [ChatGPT][play-gpt] | 正文占主要空间，输入区保持紧凑，图片留在上下文内 | [移动端聊天历史][gpt-mobile]、[数据控制及临时聊天][gpt-privacy] | [OpenAI SSE 文档][gpt-stream] | 保持输入区紧凑，明确本机记录与服务商数据接收范围 |
| [DeepSeek][play-deepseek] | 搜索与思考靠近输入框，长回复保持单列阅读 | [官方 Chat Demo][deepseek-demo] 支持停止、合规条件下继续、编辑与分支 | [Chat Completions][deepseek-api] 公开增量、结束原因与多轮输入格式 | 让断流恢复紧邻出错回复，保留版本 |
| [Claude][play-claude] | 回复与附件等辅助内容分层，常用入口少 | [Incognito Chats][claude-privacy] 有独立的历史与记忆说明 | [Anthropic SSE][claude-sse] 区分内容块、增量、结束和流内错误 | 公开数据范围，为真实中断与用户停止使用不同提示 |
| [Cherry Studio][play-cherry] | 服务商与模型有独立列表，搜索前置，条目说明简短 | [Cherry Studio Mobile][cherry-app] 公开多服务商配置与桌面导入能力 | [移动端架构][cherry-arch] 说明模型请求、会话观察与消息窗口 | 复用分层和消息窗口思路，将模型选择留在聊天内 |
| [Chatbox][play-chatbox] | 模型列表带品牌图标，文本、附件与搜索共享输入区 | [官方更新日志][chatbox-log] 记录移动端编辑、重新生成与复制 | [服务商架构][chatbox-arch] 采用 registry 和统一的流式接口，[移动端脚本][chatbox-build] 公开 Capacitor 构建入口 | 保持模型列表可搜索，恢复操作明确且由用户触发 |

继续对照时优先检查真实任务，包括新聊天、结束或中断、切换服务商和回访历史。完整截图与第三方品牌资产留作本地研究参考。

2026-11-24 复看上述商店截图，并用公开文档复核长会话的几个核心操作。商店截图不用于判断实现或性能。

| 产品 | 回访历史的公开证据 | Serein 的实现取舍 |
| --- | --- | --- |
| ChatGPT for Android | [搜索帮助][gpt-search]注明可从侧栏按标题或消息找回会话 | 保留设在侧栏的本机搜索，打开时直接定位匹配消息 |
| DeepSeek | [Chat Demo][deepseek-demo]保留消息分支，支持编辑、重新生成和历史导出。它是 Web 示例，不代表 Android 内部实现 | 窗口只读取当前分支，导出仍遍历整条分支 |
| Claude by Anthropic | [Android 帮助][claude-manage]把重命名和删除放在会话操作中 | 保留现有会话菜单，独立于单条回复和消息窗口 |
| Cherry Studio Mobile | [公开渲染设计][cherry-stream]将历史窗口和正在生成的消息分开 | 最新窗口显示流式回复，向前翻阅不变更模型请求；不引入 Agent Runtime |
| Chatbox | [v1.24.4 更新记录][chatbox-log]记录改善历史加载、分支与重生成时的位置保留 | 验证目录定位和回到最新；已渲染窗口不改变回复版本 |

Android 13 单次配对基准：用同一份已加密、含 1,200 轮合成 Markdown 的会话，从冷启动到自动化工具读到最新回复，0.0.23 为 58.45 秒，0.0.24 窗口候选版为 4.18 秒。时间包含 ADB 和 UI 采样，不代表真机加载时间；系统分别记录到 743 和 49 帧的跳帧事件。另用最终候选版导出并核对了全部 1,200 轮。

[play-gpt]: https://play.google.com/store/apps/details?id=com.openai.chatgpt
[play-deepseek]: https://play.google.com/store/apps/details?id=com.deepseek.chat
[play-claude]: https://play.google.com/store/apps/details?id=com.anthropic.claude
[play-cherry]: https://play.google.com/store/apps/details?id=com.cherryai.cherrystudio_app
[play-chatbox]: https://play.google.com/store/apps/details?id=xyz.chatboxapp.chatbox
[gpt-mobile]: https://help.openai.com/en/articles/8142208-chatgpt-mobile-app-classic-experience
[gpt-privacy]: https://help.openai.com/en/articles/7730893-data-controls-in-chatgpt
[gpt-stream]: https://developers.openai.com/api/docs/guides/streaming-responses
[gpt-search]: https://help.openai.com/en/articles/10056348-finding-your-chats-projects-and-files-in-chatgpt
[deepseek-demo]: https://github.com/deepseek-ai/chat-demo/blob/main/README.zh-CN.md
[deepseek-api]: https://api-docs.deepseek.com/api/create-chat-completion/
[claude-privacy]: https://support.claude.com/en/articles/12260368-use-incognito-chats
[claude-sse]: https://platform.claude.com/docs/en/build-with-claude/streaming
[claude-manage]: https://support.claude.com/en/articles/8230524-delete-or-rename-a-conversation
[cherry-app]: https://github.com/CherryHQ/cherry-studio-app
[cherry-arch]: https://github.com/CherryHQ/cherry-studio-app/blob/main/docs/references/architecture-overview.md
[cherry-stream]: https://github.com/CherryHQ/cherry-studio-app/blob/main/docs/references/chat/streaming-and-rendering.md
[chatbox-log]: https://chatboxai.app/en/help-center/changelog
[chatbox-arch]: https://github.com/chatboxai/chatbox/blob/main/docs/technical/ai-providers.md
[chatbox-build]: https://github.com/chatboxai/chatbox/blob/main/package.json
