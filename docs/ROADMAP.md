# Serein 迭代路线

少即是多。Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.

先保证每次消息能发出、能恢复、能找回，再打磨聊天界面。闪退、界面无响应、丢消息、重复发送和隐私回归阻断当轮交付。每轮独立验证后推送 GitHub，发行包维持 Android 13+、原发行签名和小于 64 MB 的限制。

## 接下来的迭代

按验收结果推进，不用固定日期代替验收。每轮只选一个主要聊天场景；出现阻断问题时，暂停外观迭代，先修复并复测。

| 顺序 | 状态 | 本轮交付 | 验收 |
| --- | --- | --- | --- |
| 0.0.25 · 发送稳定性 | 已推送 | 密集 Markdown 回复用纯文本预览，历史、复制和导出保留原文 | Android 13 重放已复现卡死的 18,000 段回复，确认无新增应用崩溃或 ANR，能操作、冷启动回读和完整导出；Android 17 用在线测试服务商连续发送并验证恢复 |
| 发送回归基线 | 已验收 | 用[独立测试包](../scripts/test-chat-regression.ps1)重放发送、断流、停止、重试与恢复 | Android 13、17 模拟器均通过：核对每台 8 个预期请求、取消时零请求、断流和重试保留版本与独立草稿、冷启动回读，无新增 Serein 崩溃或 ANR。在线服务商冒烟测试单独记录 |
| 下一轮 · 聊天界面 | 待办 | 统一消息区、输入框、等待/流式/失败状态的字体、留白和控件层级 | 比较改动前后截图，覆盖窄屏、键盘展开、浅色与深色、放大字体和减少动效；发送与阅读路径不增加操作步骤，并重跑对话回归 |
| 随后 · 导航与无障碍 | 待办 | 打磨侧栏、模型选择器以及回复操作的视觉一致性与可访问性 | 检查横屏、触控目标、TalkBack 焦点顺序、对比度和系统返回手势；常用动作仍可独立完成 |
| 阶段收尾 · Beta 候选 | 待办 | 汇总稳定性、长历史和覆盖升级证据，评估是否达到候选版门槛 | Android 13 及较新 Android 回归矩阵通过，复核 1,200 轮历史、签名覆盖安装和数据保留；无已知阻断问题才进入候选。不把 Markdown 导出当作可恢复备份 |

## 每轮交付规则

1. 选一个用户场景，写明当前行为、预期结果和复现方法。优先沿用已有控件和设计语言；新功能与动效必须有可说明的聊天价值。
2. 跑 `:app:chatChecks` 和受影响的模拟器回归。改 UI 时保留前后对照；涉及安装时核对 APK 版本、签名和大小。用合成消息验证，不将 Key、聊天数据或原始设备日志提交到仓库。
3. 记录已通过、未覆盖和已知限制，提交当前轮的相关改动，推送 `xiaoycnj/Serein_AI` 后核对远端 SHA。推送源码不等于发布 APK；发应用内更新时，另验 GitHub 发行资产的版本、签名和摘要。

参考产品时，记录来源、观察日期、交互证据和 Serein 的取舍。不从截图推断内部实现或性能，也不复制专有代码、品牌素材和整套界面。复用开源代码或素材前，按具体版本核对许可证、保留署名及必要声明；优先选 MIT、CC0 或其他可兼容且义务清楚的资源。没有授权的实现只研究可见行为，由 Serein 独立实现。

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
