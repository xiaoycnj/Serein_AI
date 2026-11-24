# Serein 迭代路线

少即是多。Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.

按安全、功能、外观、稳定的顺序安排常规更新。聊天卡死、数据损坏等问题先发热修。每轮独立验证后推送 GitHub，发行包维持 Android 13+、原发行签名和小于 64 MB 的限制。

| 顺序 | 用户场景 | 本轮交付 | 验收 |
| --- | --- | --- | --- |
| 热修 · 0.0.18 | 一条大表格回复让聊天失去响应 | 限制表格解析和预览规模，保留 Markdown 原文 | 从上版覆盖安装，回访历史。模拟器走普通流、断流、主动停止及大表格 |
| 1 · 安全 · 0.0.19 | 同一条聊天可能发送给多个服务商 | 发送前展示接收方和数据范围，改动后重新确认，允许清除已记住的确认 | 取消不发请求，批准才发送。验证更换接收方、清除确认与重启 |
| 2 · 功能 · 0.0.20 | 断流后希望再试一次，或者先保留已收到的正文 | 在中断回复旁提供明确的重试操作，保持原分支和草稿 | 断流、主动停止、重启、切换版本及重试失败均可回到已有内容 |
| 热修 · 0.0.21 | 大段流式回复在首字动效期间短暂消失 | 限制模糊动效的绘制范围，完整保留回复 | Android 13 覆盖安装。验证 3,200 行回复、历史回访、Markdown 导出及生成中切换前后台 |
| 3 · 外观 · 0.0.22 | 在小屏和键盘打开时找到合适模型 | 在同一行显示搜索、服务商设置与关闭，旋转时释放选择器窗口 | 检查浅色、深色、1.8 倍字号、窄屏、横屏、键盘和减少动效 |
| 4 · 稳定 · 0.0.23 | 回复结束或切换模型时遇到慢写入 | 将会话和服务商配置的序列化、加密及写盘移到后台，成功后才更新本机记录 | Android 13、17 模拟器从 0.0.22 覆盖安装；Android 13 流式 3,200 行完整导出、写盘阻塞 9.78 秒时侧栏仍响应、旋转无重复、写入失败恢复草稿 |
| 后续 · 长历史 | 回访累积了大量消息的会话，快速滚动和定位 | 测量长历史首次绘制与滚动，评估按需显示消息 | 验证完整导出、搜索跳转、分支、重试与附件仍然可用 |

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

[play-gpt]: https://play.google.com/store/apps/details?id=com.openai.chatgpt
[play-deepseek]: https://play.google.com/store/apps/details?id=com.deepseek.chat
[play-claude]: https://play.google.com/store/apps/details?id=com.anthropic.claude
[play-cherry]: https://play.google.com/store/apps/details?id=com.cherryai.cherrystudio_app
[play-chatbox]: https://play.google.com/store/apps/details?id=xyz.chatboxapp.chatbox
[gpt-mobile]: https://help.openai.com/en/articles/8142208-chatgpt-mobile-app-classic-experience
[gpt-privacy]: https://help.openai.com/en/articles/7730893-data-controls-in-chatgpt
[gpt-stream]: https://developers.openai.com/api/docs/guides/streaming-responses
[deepseek-demo]: https://github.com/deepseek-ai/chat-demo/blob/main/README.zh-CN.md
[deepseek-api]: https://api-docs.deepseek.com/api/create-chat-completion/
[claude-privacy]: https://support.claude.com/en/articles/12260368-use-incognito-chats
[claude-sse]: https://platform.claude.com/docs/en/build-with-claude/streaming
[cherry-app]: https://github.com/CherryHQ/cherry-studio-app
[cherry-arch]: https://github.com/CherryHQ/cherry-studio-app/blob/main/docs/references/architecture-overview.md
[chatbox-log]: https://chatboxai.app/en/help-center/changelog
[chatbox-arch]: https://github.com/chatboxai/chatbox/blob/main/docs/technical/ai-providers.md
[chatbox-build]: https://github.com/chatboxai/chatbox/blob/main/package.json
