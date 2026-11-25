package app.serein.historyqa;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class ReleaseReply extends Instrumentation {
    private static final String CHAT = "app.serein.chat";
    private static final String TEXT = "android.widget.TextView";
    private static final String EDIT = "android.widget.EditText";
    private static final String SCROLL = "android.widget.ScrollView";
    private static final String COMPOSER = "发消息";
    private static final String SEND = "发送";
    private static final String STOP = "停止生成";
    private static final String SAVING = "正在保存";
    private static final String CONFIRM = "发送给 Serein Upgrade Fixture";
    private static final String DEMO_REPLY = "收到。这是一条离线演示回复。";
    private static final String DEMO_HEADING = "阅读示例";
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^(?:[0-9]{1,2}\\. |• +)\\S");
    private static final Pattern FAILURE = Pattern.compile("^(?:服务商(?:拒绝访问|返回 HTTP|限流|暂时繁忙|资源不足)"
            + "|无法验证 HTTPS|无法找到服务商|连接超时|请求失败|回复未完成|已停止|模型达到|模型没有返回|收到回复，但无法保存)");

    private UiAutomation ui;
    private Bundle args;
    private String stage = "setup";
    private String mode, marker, prompt;

    @Override public void onCreate(Bundle extras) {
        super.onCreate(extras);
        args = extras;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            ui = getUiAutomation();
            mode = args.getString("mode", "");
            marker = args.getString("marker", "");
            require(marker.matches("SEREIN-QA-[a-f0-9]{10}-[0-9]{1,2}"), "Invalid test marker");
            boolean liveRich = mode.equals("rich") || mode.equals("restore");
            boolean offlineRich = mode.equals("demo") || mode.equals("restore-demo");
            prompt = liveRich ? "Write a Markdown heading " + marker
                    + " followed by 12 numbered five-word test items, then a separate final line " + marker + "-END. No links."
                    : offlineRich ? "Markdown" : mode.equals("short") ? "Reply with exactly " + marker + " and nothing else."
                    : "ReleaseReply " + marker;
            require(liveRich || offlineRich || mode.equals("short") || mode.equals("final-only"), "Invalid test mode");

            stage = "composer";
            await(n -> composer(n) && n.isEnabled());
            boolean restore = mode.startsWith("restore");
            boolean previousReplyReady = false;
            if (mode.equals("final-only")) {
                send();
                stage = "identical earlier reply";
                Answer previous = awaitComplete(prompt, DEMO_REPLY);
                require(previous.complete && previous.visible, "Could not prepare the identical-prompt control");
                previousReplyReady = true;
            }
            if (!restore) send();

            stage = "reply";
            String firstPartial = "", largestPartial = "";
            String expectedStart = liveRich ? marker : offlineRich ? DEMO_HEADING
                    : mode.equals("short") ? "SEREIN-QA-" : "收到";
            boolean returned = false;
            long deadline = SystemClock.uptimeMillis() + (offlineRich || mode.equals("final-only") ? 15_000 : 90_000);
            do {
                List<AccessibilityNodeInfo> window = snapshot();
                boolean busy = visible(window, n -> label(n, STOP));
                boolean terminal = !busy && !visible(window, n -> label(n, SAVING))
                        && visible(window, n -> composer(n) && n.isEnabled() && !label(n, prompt));
                checkForFailure(window);
                Answer answer = answer(window, prompt);
                if (busy && answer.visible && answer.body.startsWith(expectedStart)) {
                    if (firstPartial.isEmpty()) firstPartial = answer.body;
                    if (answer.body.startsWith(firstPartial) && answer.body.length() > largestPartial.length()) {
                        largestPartial = answer.body;
                    }
                    if ((liveRich || offlineRich) && !restore && !returned && largestPartial.length() > firstPartial.length()) {
                        stage = "background and return";
                        leaveAndReturn();
                        returned = true;
                        stage = "reply after return";
                        continue;
                    }
                }
                if (terminal && answer.visible && answer.complete) {
                    require(matchesFinal(answer.body), "Completed turn did not contain the expected reply");
                    boolean streamed = !firstPartial.isEmpty() && largestPartial.length() > firstPartial.length()
                            && answer.body.length() >= largestPartial.length() && answer.body.startsWith(firstPartial);
                    if (mode.equals("final-only")) {
                        require(!streamed, "A final-only reply was accepted as streaming");
                        require(previousReplyReady, "The earlier identical prompt did not finish before this send");
                        require(answer(window, prompt + " unmatched").body.isEmpty(), "Reply matched a different prompt");
                    } else if ((liveRich || offlineRich) && !restore) {
                        require(streamed && returned, "Reply completed without two growing in-flight samples and a return");
                    }
                    result.putString("stream", "\nRELEASE_REPLY_PASS " + mode
                            + "; in-flight lengths " + firstPartial.length() + " -> " + largestPartial.length()
                            + "; final length " + answer.body.length() + "\n");
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(answer.body.getBytes(StandardCharsets.UTF_8));
                    result.putString("reply_sha256", String.format(Locale.ROOT, "%064x", new BigInteger(1, digest)));
                    status = Activity.RESULT_OK;
                    break;
                }
                SystemClock.sleep(45);
            } while (SystemClock.uptimeMillis() < deadline);
            require(status == Activity.RESULT_OK, "No completed reply on the matching turn before timeout");
        } catch (Exception | AssertionError failure) {
            String detail = failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName();
            result.putString("stream", "\nRELEASE_REPLY_FAIL at " + stage + ": " + detail + "\n");
        }
        finish(status, result);
    }

    private boolean matchesFinal(String body) {
        if (mode.equals("short")) return body.equals(marker);
        if (mode.equals("final-only")) return body.equals(DEMO_REPLY);
        if (mode.endsWith("demo")) return body.startsWith(DEMO_HEADING) && body.endsWith("CommonMark")
                && body.contains("val ready = true");
        if (!body.startsWith(marker + "\n") || !body.endsWith("\n" + marker + "-END")) return false;
        var items = LIST_ITEM.matcher(body);
        int count = 0;
        while (items.find()) count++;
        return count == 12;
    }

    private Answer awaitComplete(String expectedPrompt, String expectedBody) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            List<AccessibilityNodeInfo> window = snapshot();
            checkForFailure(window);
            Answer candidate = answer(window, expectedPrompt);
            boolean busy = visible(window, n -> label(n, STOP)) || visible(window, n -> label(n, SAVING));
            boolean ready = visible(window, n -> composer(n) && n.isEnabled());
            if (!busy && ready && candidate.visible && candidate.complete) {
                require(candidate.body.equals(expectedBody), "The duplicate-reply control did not match its expected fixture");
                return candidate;
            }
            SystemClock.sleep(45);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The duplicate-reply control did not complete");
    }

    private static void checkForFailure(List<AccessibilityNodeInfo> window) {
        for (AccessibilityNodeInfo node : window) {
            if (!node.isVisibleToUser() || !is(node, TEXT)) continue;
            var reason = FAILURE.matcher(text(node));
            // Only report the matched fixed prefix; never emit raw model or provider text.
            if (reason.find()) throw new AssertionError("App reported: " + reason.group());
        }
    }

    private void send() {
        stage = "send";
        Bundle value = new Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
        require(await(n -> composer(n) && n.isEnabled()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value),
                "Composer rejected the QA prompt");
        clickSend();
        String recipient = args.getString("recipient", "");
        if (!recipient.isEmpty()) {
            stage = "recipient confirmation and cancellation";
            await(n -> label(n, CONFIRM));
            await(n -> text(n).contains(recipient));
            act(await(n -> label(n, "取消")));
            await(n -> composer(n) && n.isEnabled() && text(n).equals(prompt));
            clickSend();
            await(n -> label(n, CONFIRM));
            await(n -> text(n).contains(recipient));
            clickSend();
        }
    }

    private void clickSend() {
        act(await(n -> n.isEnabled() && n.isClickable() && label(n, SEND)));
    }

    private void leaveAndReturn() throws Exception {
        require(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME), "Could not leave via Home");
        long deadline = SystemClock.uptimeMillis() + 5_000;
        do {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null && !CHAT.contentEquals(root.getPackageName())) {
                try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                        ui.executeShellCommand("am start -W -n " + CHAT + "/.MainActivity"))) {
                    byte[] buffer = new byte[512];
                    while (output.read(buffer) != -1) { /* Wait until the activity returns. */ }
                }
                return;
            }
            SystemClock.sleep(45);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Serein did not leave the foreground");
    }

    // The transcript exposes each turn as the prompt, an assistant heading, then its reply and actions.
    private static Answer answer(List<AccessibilityNodeInfo> window, String prompt) {
        for (AccessibilityNodeInfo view : window) {
            if (!is(view, SCROLL)) continue;
            List<AccessibilityNodeInfo> transcript = new ArrayList<>();
            collect(view, transcript);
            boolean matched = false, heading = false, complete = false;
            AccessibilityNodeInfo reply = null;
            for (AccessibilityNodeInfo node : transcript) {
                if (is(node, TEXT) && !node.isHeading() && text(node).equals(prompt)) {
                    matched = true;
                    heading = complete = false;
                    reply = null;
                } else if (matched && is(node, TEXT) && node.isHeading()) {
                    if (heading) { // Another turn follows this prompt; it is not the current turn.
                        matched = false;
                        reply = null;
                    } else heading = true;
                } else if (matched && heading && reply == null && is(node, TEXT) && !text(node).isBlank()) {
                    reply = node;
                } else if (reply != null && label(node, "重新生成")) {
                    complete = true;
                }
            }
            if (reply != null) return new Answer(text(reply).strip(), reply.isVisibleToUser(), complete);
        }
        return new Answer("", false, false);
    }

    private static final class Answer {
        final String body;
        final boolean visible, complete;
        Answer(String body, boolean visible, boolean complete) {
            this.body = body;
            this.visible = visible;
            this.complete = complete;
        }
    }

    private List<AccessibilityNodeInfo> snapshot() {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(ui.getRootInActiveWindow(), nodes);
        return nodes;
    }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes) {
        if (node == null || !CHAT.contentEquals(Objects.toString(node.getPackageName(), ""))) return;
        nodes.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), nodes);
    }

    private AccessibilityNodeInfo await(Predicate<AccessibilityNodeInfo> check) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            for (AccessibilityNodeInfo node : snapshot()) if (node.isVisibleToUser() && check.test(node)) return node;
            SystemClock.sleep(45);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected UI control not found");
    }

    private static boolean visible(List<AccessibilityNodeInfo> nodes, Predicate<AccessibilityNodeInfo> check) {
        return nodes.stream().anyMatch(n -> n.isVisibleToUser() && check.test(n));
    }

    private static void act(AccessibilityNodeInfo node) {
        require(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Could not click the expected control");
    }

    private static boolean is(AccessibilityNodeInfo node, String name) { return name.equals(node.getClassName()); }
    private static boolean composer(AccessibilityNodeInfo node) {
        return is(node, EDIT) && (COMPOSER.equals(Objects.toString(node.getHintText(), "")) || label(node, COMPOSER));
    }
    private static String text(AccessibilityNodeInfo node) { return Objects.toString(node.getText(), ""); }
    private static boolean label(AccessibilityNodeInfo node, String label) {
        return label.equals(text(node)) || label.equals(Objects.toString(node.getContentDescription(), ""));
    }

    private static void require(boolean value, String failure) {
        if (!value) throw new AssertionError(failure);
    }
}
