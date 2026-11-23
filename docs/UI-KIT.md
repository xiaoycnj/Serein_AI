# Serein 控件预设

控件使用现有 Material Components 与 AppCompat。表面分层参考 [AstraAgent Design System](https://github.com/xiaoycnj/AstraAgent/blob/1a9d7d5d88fa36f993bbd2cc8e2a3dd2a6c7449f/docs/DESIGN-SYSTEM.md)，颜色仍取 Serein 的当前配色。

| 预设 | 入口 | 用于 |
| --- | --- | --- |
| 操作按钮、图标按钮、输入、开关与滑杆 | `UiKit` | 聊天和设置 |
| 确认框、表单、操作菜单 | `UiOverlays.dialog` / `show` / `menuRows` | 会话、消息菜单、服务商、代码和表格预览 |
| 短提示 | `UiOverlays.notice` | 替代系统 Toast；在聊天中放在输入框上方 |
| 轮廓下拉 | `UiKit.dropdown` | 外观设置中的背景和聊天材质；本机图片仍用系统选择器 |
| 通用背景、阅读容器、底部面板 | `UiKit.surface` / `readingSurface` / `sheetSurface` | 聊天、交互卡片、模型选择器 |

敏感弹窗由调用方开启截图保护；文件与图片权限继续由系统处理。需要用户修正的表单错误应就地显示。减少动效时取消弹窗动画与短提示的淡入淡出，模型列表加载保留文字状态。

验收：在浅色、深色、自定义主题色及大字号下打开背景和材质下拉；取消图片选择应保留原背景。检查会话菜单、编辑表单和代码预览的返回键、键盘和长文本布局。检查生成期间及开启减少动效后的提示位置。构建脚本会验证 APK 签名，并拒绝达到 64,000,000 字节的 APK。
