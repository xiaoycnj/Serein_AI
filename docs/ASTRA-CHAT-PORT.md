# AstraAgent 聊天功能移植

这次只移植聊天需要的功能，Android 界面沿用 Serein 的 Material 控件，不嵌入 AstraAgent 网页。源码参考固定在 AstraAgent 的 `1a9d7d5`（MIT，许可证已随包分发）。

| # | 功能 | Serein 入口与行为 | AstraAgent 来源 |
| --- | --- | --- | --- |
| 1 | 本机搜索会话 | 侧栏顶部搜索标题、提问和回复，结果可直接跳到命中消息。可勾选包含归档；不搜索未选中的回复版本。 | [session_search.py][search] |
| 2 | 归档与恢复 | 会话选项 → 归档会话；侧栏「已归档」可以打开、恢复或删除。置顶与消息版本保留。 | [navigation.js][archives] |
| 3 | 消息目录 | 聊天顶部的列表图标，按当前版本的提问预览跳到任一轮。 | [conversation.js][navigation] |
| 4 | 回到最新 | 向上翻阅时，点聊天区右下角的箭头回到底部并重新跟随流式回复。 | [conversation.js][navigation] |
| 5 | 从此处分支 | 回复下方的更多菜单 → 从此消息创建分支。复制到该轮为止的当前路径、卡片和选择，不改动原会话，也不发送请求。 | [enhancements.js][fork] |
| 6 | 引用到草稿 | 更多菜单 → 引用到草稿。将回复追加为 Markdown 引用，保持已有输入，超长时只截引用。 | [rich-chat.js][quote] |
| 7 | 复制代码块 | 更多菜单 → 代码与表格 → 代码块 → 复制代码。不包含 Markdown 围栏。 | [workbench.js][copy-code] |
| 8 | 代码自动换行 | 在代码块预览切换「自动换行」。关闭时可横向滚动，切换不改动原代码。 | [rich-chat.js][rich] |
| 9 | 保存代码文件 | 在预览点「保存代码」，用系统选择器保存源文本。未知语言或 HTML、SVG 等使用 `.txt`。 | [rich-chat.js][rich] |
| 10 | 复制表格 | 代码与表格 → 选择表格 → 复制表格，以 TSV 形式复制。 | [rich-chat.js][rich] |
| 11 | 导出 CSV | 在表格预览点「导出 CSV」，以 UTF-8 保存完整表格。 | [rich-chat.js][rich] |
| 12 | 导出 Markdown 会话 | 侧栏会话选项 → 导出 Markdown，只含所选版本的已保存内容。 | [app.js][export] |

搜索只在当前进程内运行，不额外保存索引或搜索词。会话和归档仍走原有加密存储。显式复制的内容进入系统剪贴板，导出的文件不再受 Serein 内部加密保护。Markdown 保留聊天原文，不额外包含服务商配置、账户资料、摘要、其他回复版本或卡片选择。表格导出为公式样式的字段加文本前缀，但不能保证经其他应用二次保存后仍安全。

无需 Key 可验收：在离线演示发送 `Markdown`，完成后打开「代码与表格」检查复制、换行和保存；长会话可查目录和回到最新。在侧栏归档、搜索、恢复该会话，再从较早一轮新建分支，核对原会话不变。

[search]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/session_search.py#L58
[archives]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/features/navigation.js#L22
[navigation]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/conversation.js#L134
[fork]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/enhancements.js#L33
[quote]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/rich-chat.js#L157
[copy-code]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/workbench.js#L21
[rich]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/rich-chat.js#L28
[export]: https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/web/assets/js/app.js#L367
