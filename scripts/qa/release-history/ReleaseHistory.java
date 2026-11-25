package app.serein.historyqa;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Predicate;

public final class ReleaseHistory extends Instrumentation {
    private static final String CHAT = "app.serein.chat";
    private static final String COMPOSER = "\u53d1\u6d88\u606f";
    private static final String PICKER = "\u9009\u62e9\u6a21\u578b\uff0c\u5f53\u524d \u79bb\u7ebf\u6f14\u793a";
    private static final String SEND = "\u53d1\u9001";
    private static final String DEMO = "\u6536\u5230\u3002\u8fd9\u662f\u4e00\u6761\u79bb\u7ebf\u6f14\u793a\u56de\u590d\u3002";
    private static final String DRAWER = "\u4f1a\u8bdd";
    private static final String DIRECTORY = "\u6d88\u606f\u76ee\u5f55";
    private static final String LATEST = "\u56de\u5230\u6700\u65b0";
    private static final String OLDER = "\u8f83\u65e9\u6d88\u606f";
    private static final String NEWER = "\u8f83\u65b0\u6d88\u606f";
    private static final String SEARCH = "\u641c\u7d22\u4f1a\u8bdd";
    private static final String QUERY = "\u4f1a\u8bdd\u6807\u9898\u6216\u5185\u5bb9";
    private static final String OPTIONS = "\u4f1a\u8bdd\u9009\u9879 \u00b7 ";
    private static final String EXPORT = "\u5bfc\u51fa Markdown";
    private static final String LOCATION = "\u9009\u62e9\u4fdd\u5b58\u4f4d\u7f6e";
    private UiAutomation ui;
    private Bundle arguments;
    private String stage;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            ui = getUiAutomation();
            AccessibilityServiceInfo service = ui.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(service);
            int turns = Integer.parseInt(arguments.getString("turns"));
            String name = arguments.getString("filename");
            if (turns < 65 || turns > 1200 || name == null || !name.matches("serein-history-[a-f0-9]+\\.md")) {
                throw new IllegalArgumentException("Invalid test arguments");
            }
            stage = "start";
            openChat();
            waitFor("offline demo", label(PICKER));
            for (int i = 1; i <= turns; i++) {
                stage = "send " + i;
                String message = prompt(i);
                enterAndSend(message);
                waitFor("saved prompt " + i, n -> label(message).test(n)
                        && !eq(n.getClassName(), "android.widget.EditText"));
                waitFor("writable composer", n -> edit(CHAT, COMPOSER).test(n) && n.isEnabled());
                if (i % 100 == 0 || i == turns) progress("Saved turn " + i);
            }
            waitFor("offline reply", label(DEMO));

            stage = "directory";
            click(DIRECTORY);
            click("1 \u00b7 " + prompt(1));
            waitFor("oldest prompt", label(prompt(1)));
            click(LATEST);
            waitFor("latest prompt", label(prompt(turns)));

            stage = "search";
            click(DRAWER);
            click(SEARCH);
            int target = turns - 29;
            setText(waitFor("history query", edit(CHAT, QUERY)), prompt(target));
            click(prompt(1));
            waitFor("search target", label(prompt(target)));

            stage = "paging";
            scrollToOlder();
            click(OLDER);
            click(NEWER);
            click(LATEST);
            waitFor("latest after paging", label(prompt(turns)));

            stage = "cold reload";
            shell("am force-stop " + CHAT);
            openChat();
            waitFor("latest after reload", label(prompt(turns)));
            waitFor("reply after reload", label(DEMO));

            stage = "export";
            click(DRAWER);
            click(OPTIONS + prompt(1));
            click(EXPORT);
            click(LOCATION);
            Predicate<AccessibilityNodeInfo> nameField = n -> documents(n)
                    && eq(n.getClassName(), "android.widget.EditText");
            waitFor("document picker", nameField);
            if (find(n -> documents(n) && label("Downloads").test(n) && hasParentId(n, "toolbar")) == null) {
                AccessibilityNodeInfo roots = waitFor("document roots", n -> documents(n)
                        && (eq(n.getContentDescription(), "Show roots") || eq(n.getContentDescription(), "Show roots drawer")));
                act(roots, AccessibilityNodeInfo.ACTION_CLICK, "document roots");
                act(waitFor("Downloads root", n -> documents(n) && label("Downloads").test(n) && hasParentId(n, "roots_list")),
                        AccessibilityNodeInfo.ACTION_CLICK, "Downloads root");
            }
            setText(waitFor("export name", nameField), name);
            act(waitFor("Save", n -> documents(n) && (label("Save").test(n) || label("SAVE").test(n)
                    || String.valueOf(n.getViewIdResourceName()).endsWith(":id/action_menu_save"))),
                    AccessibilityNodeInfo.ACTION_CLICK, "Save");
            waitFor("app after export", n -> eq(n.getPackageName(), CHAT));
            progress("PASS: UI sends, directory, search, paging and cold reload; requested Markdown export");
            result.putString("stream", "\nRELEASE_HISTORY_UI_PASS\n");
            code = Activity.RESULT_OK;
        } catch (Exception | AssertionError failure) {
            describeWindow();
            result.putString("stream", "\nRELEASE_HISTORY_UI_FAIL at " + stage + ": " + failure + "\n");
        }
        finish(code, result);
    }

    private void scrollToOlder() {
        for (int i = 0; i < 36 && find(label(OLDER)) == null; i++) {
            AccessibilityNodeInfo view = find(n -> eq(n.getPackageName(), CHAT) && n.isScrollable());
            if (view == null || !view.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break;
            SystemClock.sleep(120);
        }
    }

    private void openChat() throws Exception {
        shell("am start -W -n " + CHAT + "/.MainActivity");
        waitFor("composer", edit(CHAT, COMPOSER));
    }

    private void shell(String command) throws Exception {
        ParcelFileDescriptor output = ui.executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { /* Wait for the command to finish. */ }
        }
    }

    private static String prompt(int i) {
        return String.format(Locale.ROOT, "ReleaseHistory %04d", i);
    }

    private void progress(String message) {
        Bundle status = new Bundle();
        status.putString("stream", "\n" + message + "\n");
        sendStatus(0, status);
    }

    private void describeWindow() {
        if (ui == null) return;
        StringBuilder state = new StringBuilder("Visible test window:");
        find(n -> {
            if (eq(n.getClassName(), "android.widget.EditText") || label(SEND).test(n)
                    || documents(n) || !eq(n.getPackageName(), CHAT)) {
                state.append("\n").append(n.getPackageName()).append(" ").append(n.getViewIdResourceName())
                        .append(" ").append(n.getClassName()).append(" enabled=").append(n.isEnabled())
                        .append(" text=").append(n.getText()).append(" hint=").append(n.getHintText())
                        .append(" desc=").append(n.getContentDescription());
            }
            return state.length() > 3000;
        });
        progress(state.toString());
    }

    private void click(String text) {
        AccessibilityNodeInfo node = waitFor(text, n -> label(text).test(n) && n.isEnabled()
                && !eq(n.getClassName(), "android.widget.EditText"));
        act(node, AccessibilityNodeInfo.ACTION_CLICK, text);
    }

    private void enterAndSend(String message) {
        long deadline = SystemClock.uptimeMillis() + 12_000;
        Predicate<AccessibilityNodeInfo> field = n -> eq(n.getPackageName(), CHAT)
                && eq(n.getClassName(), "android.widget.EditText");
        do {
            AccessibilityNodeInfo composer = find(field);
            if (composer != null && composer.refresh() && composer.isEnabled()) {
                boolean entered = eq(composer.getText(), message) || trySetText(composer, message);
                AccessibilityNodeInfo button = entered ? find(label(SEND)) : null;
                if (button != null && button.refresh() && button.isEnabled() && label(SEND).test(button)
                        && composer.refresh() && eq(composer.getText(), message)
                        && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
            SystemClock.sleep(75);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Could not enter and send " + message);
    }

    private void setText(AccessibilityNodeInfo node, String text) {
        if (!trySetText(node, text)) throw new AssertionError("Text field rejected input: " + text);
    }

    private boolean trySetText(AccessibilityNodeInfo node, String text) {
        Bundle bundle = new Bundle();
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
    }

    private static void act(AccessibilityNodeInfo node, int action, String description) {
        for (int i = 0; node != null && i < 4; i++, node = node.getParent()) {
            if (node.performAction(action)) return;
        }
        throw new AssertionError("Could not activate " + description);
    }

    private AccessibilityNodeInfo waitFor(String description, Predicate<AccessibilityNodeInfo> predicate) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        do {
            AccessibilityNodeInfo found = find(predicate);
            if (found != null) return found;
            SystemClock.sleep(45);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.remove();
            if (node.isVisibleToUser() && predicate.test(node)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private static Predicate<AccessibilityNodeInfo> edit(String packageName, String hint) {
        return n -> eq(n.getPackageName(), packageName) && eq(n.getClassName(), "android.widget.EditText")
                && (eq(n.getHintText(), hint) || eq(n.getText(), hint));
    }

    private static Predicate<AccessibilityNodeInfo> label(String value) {
        return n -> eq(n.getText(), value) || eq(n.getContentDescription(), value);
    }

    private static boolean documents(AccessibilityNodeInfo node) {
        return String.valueOf(node.getPackageName()).endsWith(".documentsui");
    }

    private static boolean hasParentId(AccessibilityNodeInfo node, String id) {
        for (int i = 0; node != null && i < 6; i++, node = node.getParent()) {
            if (id(node, id)) return true;
        }
        return false;
    }

    private static boolean id(AccessibilityNodeInfo node, String id) {
        return String.valueOf(node.getViewIdResourceName()).endsWith(":id/" + id);
    }

    private static boolean eq(CharSequence value, String expected) {
        return value != null && expected.contentEquals(value);
    }
}
