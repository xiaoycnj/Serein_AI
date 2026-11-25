# Serein 开源依赖取舍

少即是多。允许在同一应用使用多个维护良好的开源库，但每个依赖都要对应一个具体聊天需求。Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.

## 参考边界

2026-11-25 查阅了 [ChatGPT Android 商店页](https://play.google.com/store/apps/details?id=com.openai.chatgpt)和 [OpenAI 移动客户端说明](https://help.openai.com/en/articles/8142208-using-the-chatgpt-mobile-app-classic-experience)，未在这些页面找到按版本公开的运行依赖清单。[OpenAI 的 ChatGPT Android 岗位说明](https://openai.com/careers/android-engineer-chatgpt-library-team-seattle/)提到 Kotlin 和 Jetpack Compose，这是工程方向的参考，不是当前 APK 的依赖证明。本记录不把候选库归因于 ChatGPT，也不从界面外观推断内部实现。

同日另查阅第三方示例 [skydoves/chatgpt-android](https://github.com/skydoves/chatgpt-android) 的 [README](https://github.com/skydoves/chatgpt-android/blob/main/README.md) 和 [app/build.gradle.kts](https://github.com/skydoves/chatgpt-android/blob/main/app/build.gradle.kts)。该仓库自述为演示项目，许可证为 Apache-2.0；它使用 Kotlin、Jetpack Compose、Stream Chat SDK，并列有 Hilt、Room、WorkManager 等组件。它不是 OpenAI 官方 ChatGPT 客户端的依赖清单。Serein 只把其架构分层和组件取舍作为阅读材料：现有 Java Views、OpenAI 兼容服务商 SSE、自管对话记录不需要 Stream Chat 服务或整体 Compose/Hilt 改造，因此本轮不移植示例代码，也不新增依赖。选择器、表单和消息呈现继续使用项目现有 `UiKit`、Material Views 与 CommonMark；后续只有具体缺陷及基线证明必要时再评估单项库。

## 当前决定

用 `./gradlew.bat --offline --console=plain :app:dependencies --configuration releaseRuntimeClasspath` 核对发行运行依赖。当前直接依赖为 Material Components `1.13.0`，以及 commonmark-java 与两个 GFM 扩展 `0.33.0`。AndroidX Core `1.13.0` 和 DynamicAnimation `1.1.0` 已经由现有依赖引入。此报告不是完整的许可证清单。

| 场景 | 可用库 | Serein 的选择 |
| --- | --- | --- |
| 聊天控件、读屏语义 | [Material Components](https://github.com/material-components/material-components-android/releases/tag/1.13.0)、[AndroidX AccessibilityDelegateCompat](https://developer.android.com/reference/androidx/core/view/AccessibilityDelegateCompat) | 保留现有 Views 和 `UiKit`。下一轮先用现有 API 补自定义控件的角色、名称和焦点，按 TalkBack 证据验收；不重写成 Compose。 |
| 弹簧与过渡 | [AndroidX SpringAnimation](https://developer.android.com/develop/ui/views/animations/spring-animation) | 只有拖动结束、方向反转等需要连续速度反馈时才采用。保留系统和应用的减少动效开关，不为静态文本增加弹簧。 |
| Markdown 正文 | [commonmark-java 许可证](https://raw.githubusercontent.com/commonmark/commonmark-java/commonmark-parent-0.33.0/LICENSE.txt) | 保留现有解析器、渲染预算和原文导出。避免并行维护第二套 Markdown 解析或引入整页 WebView。 |
| HTTPS 和流式回复 | [Square OkHttp](https://square.github.io/)、[OkHttp SSE 接口](https://raw.githubusercontent.com/square/okhttp/master/okhttp-sse/src/main/kotlin/okhttp3/sse/EventSource.kt) | 保留已经通过回归的 HTTPS 与 SSE 实现。本轮的 DNS 失败和 HTTP 403 不作为更换 HTTP 库的理由。若以后有明确的连接或取消问题，先用同一组流式夹具对比，保留请求上限、终止事件、重定向限制和接收方确认。 |
| 生成图片和附件缩略图 | [Coil Android Views 支持](https://coil-kt.github.io/coil/getting_started/) | 仅在测出图片解码开销或列表滚动问题时试验，不默认引入网络加载组件；不得因此开启未加密的聊天图片磁盘缓存。 |

## 引入门槛

1. 记录可复现的问题和基线，先检查已有控件、平台 API 和依赖能否解决。
2. 固定版本，审查官方许可证、传递依赖、维护状态和数据行为；将需要随包分发的许可与署名放进[应用开源声明资源](../app/src/main/assets/licenses/)。测试工具与发行运行依赖分开。
3. 对照改动前后验证 Android 13、17 的相关场景，核对签名发行包小于 64 MB。影响发送、存储或读屏的改动不沿用旧候选包的验收结论。

本次仅记录取舍，未改变发行依赖和 APK。
