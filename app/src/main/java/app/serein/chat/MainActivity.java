package app.serein.chat;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String CHAT = "chat";
    private static final String SETTINGS = "settings";
    private static final String ACCOUNT = "account";
    private static final int MATCH = -1;
    private static final int WRAP = -2;
    private static final String[] REASONING_MODES = {"none", "effort", "deepseek"};

    private record Section(String id, String title, String summary, int icon) {}

    private static final Section[] SECTIONS = {
            new Section("providers", "服务商连接", "模型与 API Key", R.drawable.ic_link),
            new Section("appearance", "外观", "主题与动效", R.drawable.ic_sun),
            new Section("plugins", "自定义插件", "尚未开放", R.drawable.ic_puzzle),
            new Section("about", "关于", "Alpha 0.0.3", R.drawable.ic_info),
            new Section("policy", "相关政策", "本机数据", R.drawable.ic_shield),
            new Section("licenses", "开源声明", "依赖与素材", R.drawable.ic_file),
    };

    private SharedPreferences prefs;
    private ChatStore store;
    private Providers providers;
    private ModelCatalog modelCatalog;
    private String providerPage;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private FrameLayout root;
    private FrameLayout drawer;
    private LinearLayout screen;
    private View drawerTrigger;
    private ScrollView chatScroll;
    private EditText input;
    private View reasoningRow;
    private ImageButton sendButton;
    private boolean imeVisible;
    private boolean sending;
    private String pendingPrompt;
    private String replyStatus;
    private Bitmap wallpaper;
    private static final int PICK_BACKGROUND = 870;
    private String page = CHAT;
    private String draft = "";
    private boolean backRegistered;
    private final OnBackInvokedCallback backCallback = this::navigateBack;

    private int paper, panel, ink, muted, line, tint, accent, onAccent;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("serein", MODE_PRIVATE);
        setRecentsScreenshotEnabled(false);
        Vault vault = new Vault(prefs);
        try {
            store = new ChatStore(prefs, vault);
            modelCatalog = ModelCatalog.load(getAssets());
            providers = new Providers(vault, modelCatalog);
        } catch (RuntimeException error) {
            new AlertDialog.Builder(this).setTitle("无法读取本机数据")
                    .setMessage("已保留原有文件。请重新启动 Serein 后再试。")
                    .setPositiveButton("关闭", (dialog, item) -> finish()).setCancelable(false).show();
            return;
        }
        if (saved != null) {
            page = saved.getString("page", CHAT);
            providerPage = saved.getString("providerPage");
            if (page.equals("provider") && providers.get(providerPage) == null) page = "providers";
        }
        getWindow().setDecorFitsSystemWindows(false);
        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            boolean now = insets.isVisible(WindowInsets.Type.ime());
            if (imeVisible != now) {
                imeVisible = now;
                updateReasoning(true);
            }
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        render();
        root.requestApplyInsets();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page);
        out.putString("providerPage", providerPage);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (backRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        super.onDestroy();
    }

    private void palette() {
        String theme = prefs.getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = theme.equals("dark") || (theme.equals("system") && systemDark);
        paper = color(dark ? "#151C18" : "#F8FAF7");
        panel = color(dark ? "#1C2520" : "#FFFFFF");
        ink = color(dark ? "#E7EEE9" : "#192722");
        muted = color(dark ? "#AFC1B5" : "#52655B");
        line = color(dark ? "#3B4940" : "#DDE5DF");
        tint = color(dark ? "#293F33" : "#E1EEE6");
        accent = color(dark ? "#ABCDB9" : "#235543");
        onAccent = dark ? color("#193023") : Color.WHITE;
        String custom = prefs.getString("theme_accent", "");
        if (custom.matches("#[0-9A-Fa-f]{6}")) {
            accent = color(custom);
            double luminance = .2126 * linearChannel(Color.red(accent)) + .7152 * linearChannel(Color.green(accent))
                    + .0722 * linearChannel(Color.blue(accent));
            onAccent = luminance > .179 ? Color.BLACK : Color.WHITE;
            tint = blend(paper, accent, dark ? .22f : .12f);
        }
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
        getWindow().setNavigationBarContrastEnforced(false);
    }

    private void render() {
        palette();
        root.removeAllViews();
        root.setBackgroundColor(paper);
        if (page.equals(CHAT)) drawBackground();
        screen = column();
        screen.setFocusableInTouchMode(true);
        root.addView(screen, new FrameLayout.LayoutParams(MATCH, MATCH));
        drawer = null;
        input = null;
        reasoningRow = null;
        sendButton = null;
        if (page.equals(CHAT)) renderChat();
        else renderPage();
        screen.requestFocus();
        updateBackCallback();
    }

    private void renderChat() {
        LinearLayout toolbar = toolbar();
        LinearLayout history = action("会话", R.drawable.ic_menu, 0, ink, 0);
        history.setOnClickListener(v -> openDrawer());
        toolbar.addView(history, new LinearLayout.LayoutParams(WRAP, WRAP));
        drawerTrigger = history;

        LinearLayout identity = column();
        identity.setGravity(Gravity.CENTER);
        Providers.Choice choice = providers.choice();
        LinearLayout label = row();
        label.setGravity(Gravity.CENTER);
        if (choice != null) label.addView(icon(modelCatalog.icon(choice.model()), 17, ink));
        TextView title = text(choice == null ? "Serein" : choice.model().title(), 16, ink, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, dp(4), 0);
        label.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        label.addView(icon(R.drawable.ic_chevron, 12, muted));
        identity.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView mode = text(choice == null ? "离线演示" : choice.provider().label, 11, muted, false);
        mode.setGravity(Gravity.CENTER);
        mode.setSingleLine(true);
        mode.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(mode);
        identity.setMinimumHeight(dp(48));
        identity.setBackground(ripple(0, 8));
        identity.setContentDescription("选择模型，当前 " + (choice == null ? "离线演示" : choice.model().title()));
        identity.setFocusable(true);
        identity.setOnClickListener(v -> chooseModel());
        Motion.press(identity, this::motionEnabled);
        toolbar.addView(identity, new LinearLayout.LayoutParams(0, WRAP, 1));
        toolbar.addView(iconButton("新建聊天", R.drawable.ic_plus, ink, 0, this::newChat));
        screen.addView(toolbar);

        chatScroll = scroll();
        chatScroll.setFillViewport(true);
        screen.addView(chatScroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        renderMessages();

        LinearLayout composer = row();
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(12), dp(3), dp(4), dp(3));
        String material = prefs.getString("material", "solid");
        int surface = material.equals("glass") ? (panel & 0x00ffffff) | 0xe5000000 : panel;
        composer.setBackground(shape(surface, material.equals("flat") ? 8 : 27, line));
        input = new EditText(this);
        input.setHint("发消息");
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setTextSize(16);
        input.setMinHeight(dp(48));
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setBackground(null);
        input.setPadding(dp(4), dp(9), dp(6), dp(9));
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4000)});
        input.setText(draft);
        input.setEnabled(!sending);
        composer.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1));

        ImageButton send = iconButton("发送", R.drawable.ic_send, onAccent, accent, this::send);
        sendButton = send;
        send.setBackground(ripple(accent, 24));
        composer.addView(send);
        updateSend(send);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft = s.toString();
                updateSend(send);
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        LinearLayout.LayoutParams composerLayout = new LinearLayout.LayoutParams(MATCH, WRAP);
        composerLayout.setMargins(dp(16), dp(8), dp(16), dp(10));
        screen.addView(buildReasoning(choice), new LinearLayout.LayoutParams(MATCH, WRAP));
        screen.addView(composer, composerLayout);
        updateReasoning(false);
    }

    private void updateSend(ImageButton button) {
        boolean ready = !draft.isBlank() && !sending;
        button.setEnabled(ready);
        button.setAlpha(ready ? 1f : .44f);
    }

    private boolean motionEnabled() {
        return !prefs.getBoolean("reduce_motion", false) && ValueAnimator.areAnimatorsEnabled();
    }

    private String[] effortLabels(Providers.Choice choice) {
        if (choice == null || choice.model().reasoning().equals("none")) return new String[]{"默认"};
        if (choice.model().reasoning().equals("deepseek")) return new String[]{"默认", "高", "最高"};
        return new String[]{"默认", "低", "中", "高"};
    }

    private int effort(Providers.Choice choice) {
        if (choice == null) return 0;
        return Math.max(0, Math.min(prefs.getInt(effortKey(choice), 0), effortLabels(choice).length - 1));
    }

    private String effortKey(Providers.Choice choice) {
        return "effort_" + choice.provider().id + "_" + choice.model().reasoning() + "_" + choice.model().id();
    }

    private View buildReasoning(Providers.Choice choice) {
        LinearLayout container = row();
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(18), 0, dp(16), 0);
        container.addView(icon(R.drawable.ic_sliders, 20, muted));
        String[] labels = effortLabels(choice);
        TextView title = text(labels.length == 1 ? "思考强度 · 由模型决定" : "思考强度 · " + labels[effort(choice)], 13, ink, true);
        title.setPadding(dp(8), 0, 0, 0);
        container.addView(title);
        SeekBar slider = new SeekBar(this);
        slider.setMax(Math.max(1, labels.length - 1));
        slider.setProgress(effort(choice));
        slider.setEnabled(labels.length > 1);
        slider.setContentDescription("思考强度");
        slider.setStateDescription(labels.length == 1 ? "由模型决定" : labels[effort(choice)]);
        slider.setMinHeight(dp(48));
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (!user || choice == null || progress >= labels.length) return;
                prefs.edit().putInt(effortKey(choice), progress).apply();
                title.setText("思考强度 · " + labels[progress]);
                bar.setStateDescription(labels[progress]);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        container.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1));
        reasoningRow = container;
        return container;
    }

    private void updateReasoning(boolean animate) {
        if (reasoningRow == null) return;
        int visibility = imeVisible && drawer == null ? View.VISIBLE : View.GONE;
        if (visibility == reasoningRow.getVisibility()) return;
        reasoningRow.animate().cancel();
        reasoningRow.setAlpha(1);
        reasoningRow.setTranslationY(0);
        reasoningRow.setScaleX(1);
        reasoningRow.setScaleY(1);
        reasoningRow.setVisibility(visibility);
        if (visibility == View.VISIBLE && animate) Motion.enter(reasoningRow, 0, dp(10), .98f, motionEnabled());
    }

    private void renderMessages() {
        chatScroll.removeAllViews();
        if (store.active == null && pendingPrompt == null && replyStatus == null) {
            LinearLayout welcome = column();
            welcome.setGravity(Gravity.CENTER);
            welcome.setPadding(dp(24), dp(24), dp(24), dp(34));
            welcome.addView(logo(78));
            TextView prompt = text("今天想聊什么？", 24, ink, true);
            prompt.setGravity(Gravity.CENTER);
            space(welcome, 14);
            welcome.addView(prompt);
            space(welcome, 18);
            View sample = action("帮我理顺今天的安排", 0, 0, accent, tint);
            sample.setOnClickListener(v -> {
                input.setText("帮我理顺今天的安排");
                input.setSelection(input.length());
            });
            welcome.addView(sample, new LinearLayout.LayoutParams(WRAP, WRAP));
            chatScroll.addView(welcome, new ScrollView.LayoutParams(MATCH, WRAP));
            return;
        }

        LinearLayout transcript = column();
        transcript.setPadding(dp(18), dp(16), dp(18), dp(12));
        for (ChatStore.Turn turn : store.active == null ? List.<ChatStore.Turn>of() : store.active.turns) {
            messageBubble(transcript, turn.prompt());
            space(transcript, 15);

            LinearLayout byline = row();
            byline.setGravity(Gravity.CENTER_VERTICAL);
            byline.addView(logo(28));
            TextView name = text(turn.model(), 13, ink, true);
            name.setPadding(dp(6), 0, 0, 0);
            byline.addView(name);
            transcript.addView(byline);
            TextView reply = text(turn.reply(), 16, ink, false);
            reply.setTextIsSelectable(true);
            reply.setLineSpacing(dp(4), 1);
            reply.setPadding(0, dp(6), 0, dp(4));
            transcript.addView(reply);
            space(transcript, 20);
        }
        if (pendingPrompt != null) messageBubble(transcript, pendingPrompt);
        if (replyStatus != null) {
            TextView status = text(replyStatus, 14, muted, false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            status.setPadding(0, dp(12), 0, dp(8));
            transcript.addView(status);
        }
        chatScroll.addView(transcript, new ScrollView.LayoutParams(MATCH, WRAP));
        ScrollView visibleTranscript = chatScroll;
        visibleTranscript.post(() -> visibleTranscript.scrollTo(0, transcript.getHeight()));
    }

    private void messageBubble(LinearLayout transcript, String message) {
        TextView user = text(message, 16, ink, false);
        user.setTextIsSelectable(true);
        user.setPadding(dp(14), dp(10), dp(14), dp(10));
        user.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * .76));
        user.setBackground(shape(tint, 18, 0));
        LinearLayout.LayoutParams bubble = new LinearLayout.LayoutParams(WRAP, WRAP);
        bubble.gravity = Gravity.END;
        transcript.addView(user, bubble);
    }

    private void send() {
        String message = draft.strip();
        if (message.isBlank() || sending) return;
        Providers.Choice choice = providers.choice();
        if (choice == null) {
            try {
                store.append(message, ChatStore.demoReply(message), "Serein");
                input.setText("");
                replyStatus = null;
                renderMessages();
            } catch (IllegalStateException error) { notice("无法保存本机聊天记录"); }
            return;
        }
        String consent = "consent_" + choice.provider().id + "_" + choice.provider().base;
        if (!prefs.getBoolean(consent, false)) {
            new AlertDialog.Builder(this).setTitle("发送给 " + choice.provider().label)
                    .setMessage(choice.provider().base + "\n\n发送时会把当前会话的消息发给此服务商。")
                    .setNegativeButton("取消", null).setPositiveButton("发送", (dialog, item) -> {
                        prefs.edit().putBoolean(consent, true).apply();
                        sendOnline(choice, message);
                    }).show();
        } else sendOnline(choice, message);
    }

    private void sendOnline(Providers.Choice choice, String message) {
        List<ChatStore.Turn> context = store.active == null ? List.of() : List.copyOf(store.active.turns);
        int level = effort(choice);
        sending = true;
        pendingPrompt = message;
        replyStatus = "等待 " + choice.model().id() + " 回复…";
        input.setText("");
        input.setEnabled(false);
        renderMessages();
        worker.execute(() -> {
            String response = null;
            String failure = null;
            try { response = ModelApi.chat(choice, context, message, level); }
            catch (Exception error) { failure = networkMessage(error); }
            String reply = response;
            String problem = failure;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                sending = false;
                pendingPrompt = null;
                replyStatus = problem;
                if (reply != null) {
                    try { store.append(message, reply, choice.model().id()); }
                    catch (IllegalStateException error) { replyStatus = "收到回复，但无法保存本机聊天记录。"; }
                }
                if (replyStatus != null) draft = message;
                if (input != null) {
                    input.setEnabled(true);
                    input.setText(draft);
                    input.setSelection(input.length());
                    updateSend(sendButton);
                    renderMessages();
                }
            });
        });
    }

    private void newChat() {
        if (sending) { notice("正在等待回复"); return; }
        if (input != null) hideKeyboard();
        try { store.open(null); }
        catch (IllegalStateException error) { notice("无法保存会话状态"); return; }
        draft = "";
        replyStatus = null;
        go(CHAT);
    }

    private void openDrawer() {
        if (drawer != null) return;
        hideKeyboard();
        drawer = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x660C1D15);
        scrim.setContentDescription("关闭侧栏");
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(v -> closeDrawer());
        drawer.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout rail = column();
        rail.setBackgroundColor(panel);
        rail.setClickable(true);
        rail.setAccessibilityPaneTitle("会话侧栏");
        rail.setPadding(dp(14), dp(6), dp(14), dp(10));
        int width = Math.min(dp(340), Math.max(dp(220), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(44)));
        drawer.addView(rail, new FrameLayout.LayoutParams(width, MATCH, Gravity.START));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(logo(40));
        TextView brand = text("Serein", 21, ink, true);
        brand.setPadding(dp(7), 0, 0, 0);
        header.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1));
        View close = iconButton("关闭侧栏", R.drawable.ic_close, ink, 0, this::closeDrawer);
        header.addView(close);
        rail.addView(header);
        space(rail, 16);
        View start = action("新建聊天", R.drawable.ic_plus, 0, accent, tint);
        start.setOnClickListener(v -> newChat());
        rail.addView(start, new LinearLayout.LayoutParams(MATCH, WRAP));
        space(rail, 14);
        ScrollView historyScroll = scroll();
        LinearLayout history = column();
        sectionTitle(history, "最近会话");
        if (store.recent.isEmpty()) {
            TextView empty = text("暂无会话", 14, muted, false);
            empty.setPadding(dp(8), dp(10), dp(8), dp(10));
            history.addView(empty);
        }
        for (ChatStore.Chat chat : store.recent) addHistoryRow(history, chat);
        historyScroll.addView(history);
        rail.addView(historyScroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        rule(rail);
        String name = prefs.getString("profile_name", "本机账户");
        addRow(rail, name, "账户", R.drawable.ic_user, () -> go(ACCOUNT));
        addRow(rail, "设置", "", R.drawable.ic_settings, () -> go(SETTINGS));
        screen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(drawer, new FrameLayout.LayoutParams(MATCH, MATCH));
        close.setFocusableInTouchMode(true);
        close.requestFocus();
        Motion.enter(rail, -dp(24), 0, 1, motionEnabled());
        updateReasoning(false);
        updateBackCallback();
    }

    private void addHistoryRow(LinearLayout parent, ChatStore.Chat chat) {
        boolean current = chat == store.active;
        LinearLayout item = column();
        item.setPadding(dp(9), dp(9), dp(9), dp(9));
        item.setMinimumHeight(dp(64));
        item.setBackground(ripple(current ? tint : 0, 8));
        item.setSelected(current);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(chat.title, 15, ink, true);
        top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        String date = DateFormat.getDateInstance(DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(chat.updatedAt);
        TextView time = text(date, 12, muted, false);
        time.setPadding(dp(7), 0, 0, 0);
        top.addView(time);
        item.addView(top);
        LinearLayout bottom = row();
        String last = chat.turns.get(chat.turns.size() - 1).prompt().replace('\n', ' ');
        TextView preview = text(last, 13, muted, false);
        preview.setMaxLines(1);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(preview, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (current) bottom.addView(icon(R.drawable.ic_check, 18, accent));
        item.addView(bottom);
        item.setFocusable(true);
        item.setOnClickListener(v -> {
            if (store.active == chat) {
                closeDrawer();
                return;
            }
            if (sending) { notice("正在等待回复"); return; }
            try { store.open(chat); }
            catch (IllegalStateException error) { notice("无法保存会话状态"); return; }
            draft = "";
            replyStatus = null;
            go(CHAT);
        });
        parent.addView(item);
    }

    private void closeDrawer() {
        if (drawer == null) return;
        root.removeView(drawer);
        drawer = null;
        screen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        drawerTrigger.requestFocus();
        updateReasoning(false);
        updateBackCallback();
    }

    private void go(String destination) {
        page = destination;
        render();
        Motion.enter(screen, 0, dp(7), .996f, motionEnabled());
    }

    private void navigateBack() {
        if (drawer != null) closeDrawer();
        else if (page.equals(SETTINGS) || page.equals(ACCOUNT)) {
            hideKeyboard();
            go(CHAT);
            openDrawer();
        } else if (page.equals("provider")) go("providers");
        else if (!page.equals(CHAT)) go(SETTINGS);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (drawer != null || !page.equals(CHAT)) navigateBack();
        else super.onBackPressed();
    }

    private void updateBackCallback() {
        boolean needsCallback = drawer != null || !page.equals(CHAT);
        if (needsCallback == backRegistered) return;
        if (needsCallback) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        backRegistered = needsCallback;
    }

    private void renderPage() {
        LinearLayout toolbar = toolbar();
        toolbar.addView(iconButton("返回", R.drawable.ic_back, ink, 0, this::navigateBack));
        String heading = switch (page) {
            case SETTINGS -> "设置";
            case ACCOUNT -> "账户";
            case "provider" -> providers.get(providerPage).label;
            default -> findSection(page).title();
        };
        TextView title = text(heading, 21, ink, true);
        title.setAccessibilityHeading(true);
        title.setPadding(dp(5), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        screen.addView(toolbar);
        ScrollView scroll = scroll();
        LinearLayout body = column();
        body.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(body);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));

        switch (page) {
            case SETTINGS -> settings(body);
            case ACCOUNT -> account(body);
            case "appearance" -> appearance(body);
            case "providers" -> providerList(body);
            case "provider" -> providerDetails(body, providers.get(providerPage));
            case "plugins" -> body.addView(text("自定义插件尚未开放。", 16, ink, false));
            case "about" -> {
                body.addView(logo(68));
                space(body, 10);
                body.addView(text("Serein", 24, ink, true));
                body.addView(text("Alpha 0.0.3 · Android 13+", 14, muted, false));
                space(body, 20);
                body.addView(text("Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.", 16, ink, false));
            }
            case "policy" -> body.addView(text("会话历史和服务商配置保存在本机，由 Android Keystore 管理的密钥加密。使用在线模型发送消息时，当前会话内容会通过 HTTPS 发给你选定的服务商。", 16, ink, false));
            case "licenses" -> {
                license(body, "Lucide 图标 · ISC 许可", "lucide");
                license(body, "Lobe Icons · MIT 许可", "lobe-icons");
                license(body, "Tabler Icons · MIT 许可", "tabler-icons");
                license(body, "LiteLLM 模型目录 · MIT 许可", "litellm");
            }
        }
    }

    private Section findSection(String id) {
        for (Section section : SECTIONS) if (section.id().equals(id)) return section;
        throw new IllegalArgumentException(id);
    }

    private void license(LinearLayout body, String title, String asset) {
        body.addView(text(title, 16, ink, true));
        space(body, 8);
        try (InputStream license = getAssets().open("licenses/" + asset + ".txt")) {
            body.addView(text(new String(license.readAllBytes(), StandardCharsets.UTF_8), 14, muted, false));
        } catch (IOException error) {
            android.util.Log.w("Serein", "Bundled license unavailable: " + asset, error);
        }
        space(body, 20);
    }

    private void chooseModel() {
        hideKeyboard();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = column();
        sheet.setPadding(dp(16), dp(8), dp(16), dp(8));
        sheet.setFocusableInTouchMode(true);
        GradientDrawable background = shape(panel, 24, 0);
        int radius = dp(24);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);

        LinearLayout heading = row();
        heading.setPadding(dp(4), 0, 0, 0);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("选择模型", 21, ink, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        heading.addView(iconButton("关闭模型选择", R.drawable.ic_close, muted, 0, dialog::dismiss));
        sheet.addView(heading);
        LinearLayout searchBar = row();
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(12), 0, dp(10), 0);
        searchBar.setMinimumHeight(dp(48));
        searchBar.setBackground(shape(paper, 8, line));
        searchBar.addView(icon(R.drawable.ic_search, 19, muted));
        EditText search = field(searchBar, "搜索模型或服务商", "", false);
        search.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1));
        search.setBackground(null);
        search.setMinimumHeight(dp(48));
        search.setPadding(dp(10), dp(4), dp(4), dp(4));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sheet.addView(searchBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        space(sheet, 8);

        FrameLayout resultArea = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        resultArea.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView empty = text("没有匹配的模型", 14, muted, false);
        empty.setGravity(Gravity.CENTER);
        empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        resultArea.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        list.setEmptyView(empty);
        sheet.addView(resultArea, new LinearLayout.LayoutParams(MATCH, 0, 1));

        Providers.Choice current = providers.choice();
        List<Providers.Choice> options = new ArrayList<>();
        options.add(null);
        for (Providers.Provider provider : providers.all) {
            for (Providers.Model model : provider.models) options.add(new Providers.Choice(provider, model));
        }
        if (current != null) {
            options.remove(current);
            options.add(0, current);
        }
        List<Providers.Choice> filtered = new ArrayList<>(options);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Providers.Choice getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                ModelRow row = recycled instanceof ModelRow modelRow ? modelRow : new ModelRow();
                Providers.Choice choice = getItem(position);
                row.bind(choice, java.util.Objects.equals(choice, current));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Providers.Choice choice = filtered.get(position);
            changeChoice(dialog, choice == null ? null : choice.provider(), choice == null ? null : choice.model());
        });
        watch(search, query -> {
            String terms = query.strip().toLowerCase(Locale.ROOT);
            filtered.clear();
            for (Providers.Choice choice : options) {
                String words = choice == null ? "serein 离线演示" : choice.model().title() + " " + choice.model().id()
                        + " " + choice.model().publisher() + " " + choice.provider().label;
                if (matches(words.toLowerCase(Locale.ROOT), terms)) filtered.add(choice);
            }
            adapter.notifyDataSetChanged();
            list.setSelection(0);
        });
        rule(sheet);
        View settings = action("服务商设置", R.drawable.ic_settings, 0, ink, 0);
        settings.setOnClickListener(v -> { dialog.dismiss(); go("providers"); });
        sheet.addView(settings, new LinearLayout.LayoutParams(MATCH, WRAP));
        dialog.setContentView(sheet);
        sheet.requestFocus();
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.35f);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        var bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        int contentHeight = Math.min(496, 192 + 62 * options.size());
        window.setLayout(Math.min(bounds.width(), dp(520)), Math.min(dp(contentHeight), Math.round(bounds.height() * .65f)));
        Motion.enter(sheet, 0, dp(24), .99f, motionEnabled());
    }

    private static boolean matches(String name, String terms) {
        for (String term : terms.split("\\s+")) if (!name.contains(term)) return false;
        return true;
    }

    private void changeChoice(Dialog dialog, Providers.Provider provider, Providers.Model model) {
        try {
            providers.select(provider, model);
            dialog.dismiss();
            render();
        } catch (IllegalStateException error) { notice("无法保存模型选择"); }
    }

    private final class ModelRow extends LinearLayout {
        private final ImageView mark;
        private final TextView name;
        private final TextView detail;
        private final ImageView check;

        ModelRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(60));
            setPadding(dp(10), dp(6), dp(10), dp(6));
            mark = icon(R.drawable.ic_cpu, 36, ink);
            mark.setPadding(dp(8), dp(8), dp(8), dp(8));
            mark.setBackground(shape(paper, 8, line));
            addView(mark);
            LinearLayout labels = column();
            labels.setPadding(dp(12), 0, dp(8), 0);
            name = text("", 16, ink, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            detail = text("", 12, muted, false);
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name);
            labels.addView(detail);
            addView(labels, new LayoutParams(0, WRAP, 1));
            check = icon(R.drawable.ic_check, 19, ink);
            addView(check);
        }

        void bind(Providers.Choice choice, boolean selected) {
            Providers.Model model = choice == null ? null : choice.model();
            name.setText(model == null ? "离线演示" : model.title());
            String subtitle = model == null ? "Serein" : choice.provider().label
                    + (model.title().equals(model.id()) ? "" : " · " + model.id());
            detail.setText(subtitle);
            mark.setImageResource(model == null ? R.drawable.ic_cpu : modelCatalog.icon(model));
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            setSelected(selected);
            setBackground(ripple(selected ? tint : Color.TRANSPARENT, 8));
            if (selected) setStateDescription("当前模型");
            else setStateDescription(null);
        }
    }

    private void providerList(LinearLayout body) {
        command(body, "添加服务商", R.drawable.ic_plus, () -> editProvider(null));
        space(body, 12);
        if (providers.all.isEmpty()) body.addView(text("暂无服务商", 14, muted, false));
        for (Providers.Provider provider : providers.all) {
            addRow(body, provider.label, provider.models.size() + " 个模型 · " + provider.keys.size() + " 把 Key",
                    R.drawable.ic_link, () -> { providerPage = provider.id; go("provider"); });
            rule(body);
        }
    }

    private void providerDetails(LinearLayout body, Providers.Provider provider) {
        addRow(body, "连接设置", provider.base, R.drawable.ic_link, () -> editProvider(provider));
        space(body, 16);
        sectionTitle(body, "API Key");
        for (int index = 0; index < provider.keys.size(); index++) {
            int position = index;
            addRow(body, provider.keys.get(index).label(), index == provider.activeKey ? "使用中" : "已保存",
                    index == provider.activeKey ? R.drawable.ic_check : R.drawable.ic_shield, () -> keyOptions(provider, position));
        }
        command(body, "添加 Key", R.drawable.ic_plus, () -> editKey(provider, -1));
        space(body, 16);
        sectionTitle(body, "模型");
        command(body, "拉取模型列表", R.drawable.ic_link, () -> fetchCatalog(provider));
        command(body, "手动添加模型", R.drawable.ic_plus, () -> editModel(provider, null));
        if (provider.models.isEmpty()) body.addView(text("暂无模型", 14, muted, false));
        for (Providers.Model model : provider.models) {
            String subtitle = model.title().equals(model.id()) ? model.publisher() : model.id();
            addRow(body, model.title(), subtitle, modelCatalog.icon(model), () -> editModel(provider, model));
        }
        space(body, 22);
        command(body, "移除服务商", R.drawable.ic_close, () -> new AlertDialog.Builder(this).setTitle("移除 " + provider.label + "？")
                .setNegativeButton("取消", null).setPositiveButton("移除", (d, i) -> {
                    try { providers.remove(provider); go("providers"); }
                    catch (IllegalStateException error) { notice("无法更新本机配置"); }
                }).show());
    }

    private void editProvider(Providers.Provider old) {
        LinearLayout form = dialogBody();
        EditText name = field(form, "服务商名称", old == null ? "" : old.label, false);
        EditText base = field(form, "HTTPS 根地址（含 API 路径）", old == null ? "" : old.base, false);
        if (old != null && !old.keys.isEmpty()) base.setEnabled(false);
        saveForm(old == null ? "添加 OpenAI 兼容服务商" : "连接设置", form, false, () -> {
            String label = name.getText().toString().strip();
            if (label.isEmpty() || label.length() > 64) throw new IllegalArgumentException("请输入 1–64 个字符的服务商名称。");
            String url = Providers.httpsBase(base.getText().toString());
            Providers.Provider next = new Providers.Provider(old == null ? null : old.id, label, url,
                    old == null ? List.of() : old.keys, old == null ? List.of() : old.models, old == null ? 0 : old.activeKey);
            providers.put(next);
            providerPage = next.id;
            go("provider");
        }, null);
    }

    private void keyOptions(Providers.Provider provider, int position) {
        new AlertDialog.Builder(this).setTitle(provider.keys.get(position).label())
                .setItems(new String[]{"使用这把 Key", "编辑", "移除"}, (dialog, index) -> {
                    if (index == 0) putProvider(provider.credentials(provider.keys, position));
                    if (index == 1) editKey(provider, position);
                    if (index == 2) new AlertDialog.Builder(this).setTitle("移除这把 Key？")
                            .setNegativeButton("取消", null).setPositiveButton("移除", (d, i) -> {
                                List<Providers.Key> updated = new ArrayList<>(provider.keys);
                                updated.remove(position);
                                int selected = provider.activeKey > position ? provider.activeKey - 1 : provider.activeKey;
                                putProvider(provider.credentials(updated, selected));
                            }).show();
                }).show();
    }

    private void editKey(Providers.Provider provider, int position) {
        Providers.Key old = position < 0 ? null : provider.keys.get(position);
        LinearLayout form = dialogBody();
        EditText label = field(form, "Key 名称", old == null ? "" : old.label(), false);
        EditText secret = field(form, old == null ? "API Key" : "新 API Key（可留空）", "", true);
        saveForm(old == null ? "添加 Key" : "编辑 Key", form, true, () -> {
            String value = secret.getText().toString().strip();
            if (value.isEmpty() && old != null) value = old.secret();
            if (!value.matches("[!-~]{1,4096}")) throw new IllegalArgumentException("请输入有效的 API Key。");
            String name = label.getText().toString().strip();
            if (name.length() > 64) throw new IllegalArgumentException("Key 名称最多 64 个字符。");
            if (name.isEmpty()) name = "Key " + (provider.keys.size() + 1);
            List<Providers.Key> keys = new ArrayList<>(provider.keys);
            if (position < 0) keys.add(new Providers.Key(name, value));
            else keys.set(position, new Providers.Key(name, value));
            providers.put(provider.credentials(keys, provider.activeKey));
            secret.getText().clear();
            render();
        }, null);
    }

    private void editModel(Providers.Provider provider, Providers.Model old) {
        LinearLayout form = dialogBody();
        String originalId = old == null ? "" : old.id();
        Providers.Model initial = old == null ? modelCatalog.defaults("", provider.base) : old;
        EditText id = modelField(form, "模型 ID", originalId, 256);
        id.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView matchStatus = text("", 12, muted, false);
        matchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(matchStatus);
        EditText label = modelField(form, "显示名称", initial.label(), 128);
        EditText publisher = modelField(form, "模型发布方", initial.publisher(), 128);
        String[] iconOverride = {initial.icon()};
        TextView iconLabel = text("", 14, ink, true);
        ImageView mark = icon(R.drawable.ic_cpu, 20, ink);
        LinearLayout iconControl = row();
        iconControl.setGravity(Gravity.CENTER_VERTICAL);
        iconControl.setMinimumHeight(dp(48));
        iconControl.setPadding(dp(8), 0, dp(8), 0);
        iconControl.setBackground(ripple(tint, 8));
        iconControl.setFocusable(true);
        iconControl.addView(mark);
        iconLabel.setPadding(dp(8), 0, dp(8), 0);
        iconControl.addView(iconLabel, new LinearLayout.LayoutParams(0, WRAP, 1));
        iconControl.addView(icon(R.drawable.ic_chevron, 16, muted));
        space(form, 8);
        form.addView(iconControl, new LinearLayout.LayoutParams(MATCH, WRAP));
        Motion.press(iconControl, this::motionEnabled);
        Runnable refresh = () -> {
            String modelId = id.getText().toString().strip();
            ModelCatalog.Entry entry = modelCatalog.find(modelId);
            matchStatus.setVisibility(modelId.isEmpty() ? View.GONE : View.VISIBLE);
            matchStatus.setText(entry == null ? "本地目录未匹配" : "已匹配本地目录");
            String override = iconOverride[0];
            String glyph = override.isEmpty() && entry != null ? entry.icon() : override;
            mark.setImageResource(ModelIcons.resolve(glyph));
            iconLabel.setText("模型图标 · " + ModelIcons.option(override).label());
        };
        iconControl.setOnClickListener(v -> {
            String[] labels = ModelIcons.OPTIONS.stream().map(ModelIcons.Option::label).toArray(String[]::new);
            int selected = ModelIcons.OPTIONS.indexOf(ModelIcons.option(iconOverride[0]));
            new AlertDialog.Builder(this).setTitle("模型图标")
                    .setSingleChoiceItems(labels, selected, (dialog, position) -> {
                        iconOverride[0] = ModelIcons.OPTIONS.get(position).id();
                        refresh.run();
                        dialog.dismiss();
                    }).setNegativeButton("取消", null).show();
        });
        android.widget.RadioGroup modes = new android.widget.RadioGroup(this);
        form.addView(modes);
        for (int i = 0; i < REASONING_MODES.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(this);
            button.setId(i + 1);
            button.setMinHeight(dp(48));
            button.setText(reasoningName(REASONING_MODES[i]));
            button.setTextColor(ink);
            button.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ink, muted}));
            modes.addView(button);
        }
        modes.check(reasoningIndex(initial.reasoning()));
        Providers.Model[] previousDefaults = {modelCatalog.defaults(originalId, provider.base)};
        watch(id, value -> {
            Providers.Model defaults = modelCatalog.defaults(value.strip(), provider.base);
            Providers.Model previous = previousDefaults[0];
            replaceDefault(label, previous.label(), defaults.label());
            replaceDefault(publisher, previous.publisher(), defaults.publisher());
            if (modes.getCheckedRadioButtonId() == reasoningIndex(previous.reasoning())) {
                modes.check(reasoningIndex(defaults.reasoning()));
            }
            previousDefaults[0] = defaults;
            refresh.run();
        });
        refresh.run();
        saveForm(old == null ? "添加模型" : "模型设置", form, false, () -> {
            String modelId = id.getText().toString().strip();
            if (modelId.isEmpty() || modelId.length() > 256) throw new IllegalArgumentException("请输入 1–256 个字符的模型 ID。");
            List<Providers.Model> catalog = new ArrayList<>(provider.models);
            if (catalog.stream().anyMatch(model -> model != old && model.id().equals(modelId))) {
                throw new IllegalArgumentException("此模型已在列表中。");
            }
            Providers.Model entry = new Providers.Model(modelId, REASONING_MODES[modes.getCheckedRadioButtonId() - 1],
                    label.getText().toString().strip(), publisher.getText().toString().strip(), iconOverride[0]);
            if (old == null) catalog.add(entry);
            else catalog.set(catalog.indexOf(old), entry);
            providers.put(provider.catalog(catalog), originalId, modelId);
            render();
        }, old == null ? null : () -> {
            List<Providers.Model> catalog = new ArrayList<>(provider.models);
            catalog.remove(old);
            putProvider(provider.catalog(catalog));
        });
    }

    private EditText modelField(LinearLayout form, String label, String value, int limit) {
        TextView caption = text(label, 13, muted, true);
        caption.setPadding(dp(3), dp(8), 0, 0);
        form.addView(caption);
        EditText edit = field(form, label, value, false);
        edit.setId(View.generateViewId());
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        caption.setLabelFor(edit.getId());
        return edit;
    }

    private static int reasoningIndex(String mode) {
        for (int i = 0; i < REASONING_MODES.length; i++) if (REASONING_MODES[i].equals(mode)) return i + 1;
        return 1;
    }

    private static void replaceDefault(EditText field, String previous, String next) {
        if (!previous.equals(next) && field.getText().toString().equals(previous)) field.setText(next);
    }

    private static void watch(EditText field, java.util.function.Consumer<String> observer) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { observer.accept(s.toString()); }
            @Override public void afterTextChanged(Editable editable) {}
        });
    }

    private static String reasoningName(String mode) {
        return switch (mode) {
            case "effort" -> "思考参数 · reasoning_effort";
            case "deepseek" -> "思考参数 · DeepSeek";
            default -> "思考参数 · 模型默认";
        };
    }

    private void fetchCatalog(Providers.Provider provider) {
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("正在拉取模型")
                .setNegativeButton("取消", null).create();
        progress.show();
        worker.execute(() -> {
            List<String> ids;
            try { ids = ModelApi.models(provider); }
            catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !progress.isShowing()) return;
                    progress.dismiss();
                    notice(networkMessage(error));
                });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || !progress.isShowing()) return;
                progress.dismiss();
                Providers.Provider current = providers.get(provider.id);
                if (current != provider) return;
                List<Providers.Model> models = new ArrayList<>(current.models);
                java.util.Set<String> existing = new java.util.HashSet<>();
                for (Providers.Model model : models) existing.add(model.id());
                for (String id : ids) if (existing.add(id)) models.add(modelCatalog.defaults(id, current.base));
                if (putProvider(current.catalog(models))) notice("服务商返回 " + ids.size() + " 个模型");
            });
        });
    }

    private boolean putProvider(Providers.Provider provider) {
        try { providers.put(provider); render(); return true; }
        catch (IllegalStateException error) { notice("无法更新本机服务商配置"); return false; }
    }

    private String networkMessage(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请重试。";
        if (error instanceof javax.net.ssl.SSLException) return "无法验证 HTTPS 连接。";
        if (error instanceof java.net.UnknownHostException) return "无法找到服务商地址。";
        if (error instanceof IOException && error.getMessage() != null &&
                (error.getMessage().startsWith("服务商") || error.getMessage().startsWith("模型"))) return error.getMessage();
        return "请求失败，请检查连接与模型 ID。";
    }

    private void notice(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private void command(LinearLayout body, String title, int glyph, Runnable tap) {
        View button = action(title, glyph, 0, ink, tint);
        button.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.bottomMargin = dp(6);
        body.addView(button, params);
    }

    private LinearLayout dialogBody() {
        LinearLayout body = column();
        body.setPadding(dp(23), dp(12), dp(23), dp(8));
        body.setFocusableInTouchMode(true);
        body.setBackgroundColor(paper);
        return body;
    }

    private EditText field(LinearLayout body, String label, String value, boolean secret) {
        EditText edit = new EditText(this);
        edit.setHint(label);
        edit.setText(value);
        edit.setTextSize(15);
        edit.setTextColor(ink);
        edit.setHintTextColor(muted);
        edit.setSingleLine(true);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (secret) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            edit.setSaveEnabled(false);
        }
        body.addView(edit, new LinearLayout.LayoutParams(MATCH, WRAP));
        return edit;
    }

    private void saveForm(String title, LinearLayout form, boolean secure, Runnable save, Runnable remove) {
        ScrollView scroll = scroll();
        scroll.addView(form);
        TextView errorText = text("", 14, ink, true);
        form.addView(errorText);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorText.setVisibility(View.GONE);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (remove != null) builder.setNeutralButton("移除", (dialog, index) -> remove.run());
        AlertDialog dialog = builder.create();
        showDialog(dialog, secure);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { save.run(); dialog.dismiss(); }
            catch (IllegalArgumentException error) { errorText.setText(error.getMessage()); errorText.setVisibility(View.VISIBLE); }
            catch (IllegalStateException error) { errorText.setText("无法保存本机配置。"); errorText.setVisibility(View.VISIBLE); }
        });
    }

    private void showDialog(AlertDialog dialog, boolean secure) {
        if (secure) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!motionEnabled()) dialog.getWindow().setWindowAnimations(0);
        dialog.show();
    }

    private void settings(LinearLayout body) {
        sectionTitle(body, "聊天体验");
        for (int i = 0; i < SECTIONS.length; i++) {
            if (i == 3) {
                space(body, 20);
                sectionTitle(body, "应用信息");
            }
            Section section = SECTIONS[i];
            addRow(body, section.title(), section.summary(), section.icon(), () -> go(section.id()));
            rule(body);
        }
    }

    private void account(LinearLayout body) {
        TextView label = text("显示名称", 14, muted, true);
        body.addView(label);
        space(body, 6);
        EditText name = new EditText(this);
        name.setId(View.generateViewId());
        label.setLabelFor(name.getId());
        name.setText(prefs.getString("profile_name", "本机账户"));
        name.setTextColor(ink);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setMinHeight(dp(48));
        name.setPadding(dp(12), dp(7), dp(12), dp(7));
        name.setBackground(shape(panel, 8, line));
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
        body.addView(name);
        space(body, 14);
        View save = action("保存", R.drawable.ic_check, 0, onAccent, accent);
        save.setOnClickListener(v -> {
            String displayName = name.getText().toString().strip();
            prefs.edit().putString("profile_name", displayName.isBlank() ? "本机账户" : displayName).apply();
            navigateBack();
        });
        body.addView(save, new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private void appearance(LinearLayout body) {
        sectionTitle(body, "主题");
        LinearLayout options = row();
        String[] names = {"跟随系统", "浅色", "深色"};
        String[] values = {"system", "light", "dark"};
        for (int i = 0; i < names.length; i++) {
            String value = values[i];
            boolean selected = value.equals(prefs.getString("theme", "system"));
            LinearLayout option = action(names[i], 0, 0, selected ? onAccent : ink, selected ? accent : tint);
            option.setSelected(selected);
            option.setPadding(dp(5), dp(5), dp(5), dp(5));
            option.setOnClickListener(v -> {
                prefs.edit().putString("theme", value).apply();
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1);
            if (i > 0) lp.setMarginStart(dp(6));
            options.addView(option, lp);
        }
        body.addView(options);
        space(body, 19);
        sectionTitle(body, "主题色");
        LinearLayout swatches = row();
        String[] colors = {"#235543", "#33577B", "#76516B", "#8A4B35"};
        String[] swatchNames = {"雨松绿", "雾蓝", "暮紫", "陶土"};
        String selectedAccent = prefs.getString("theme_accent", "");
        for (int i = 0; i < colors.length; i++) {
            String value = colors[i];
            FrameLayout target = new FrameLayout(this);
            target.setContentDescription(swatchNames[i]);
            target.setSelected(value.equalsIgnoreCase(selectedAccent));
            target.setFocusable(true);
            target.setBackground(ripple(0, 8));
            View dot = new View(this);
            dot.setBackground(shape(color(value), 18, target.isSelected() ? ink : 0));
            target.addView(dot, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            if (target.isSelected()) target.addView(icon(R.drawable.ic_check, 17, Color.WHITE),
                    new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
            target.setOnClickListener(v -> setAppearance("theme_accent", value));
            Motion.press(target, this::motionEnabled);
            swatches.addView(target, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        body.addView(swatches);
        addRow(body, "自定义主题色", selectedAccent.isEmpty() ? "默认" : selectedAccent,
                R.drawable.ic_sun, this::editAccent);
        space(body, 14);
        sectionTitle(body, "聊天背景");
        String background = prefs.getString("background", "plain");
        addRow(body, "背景", switch (background) {
            case "wash" -> "主题渐变";
            case "image" -> "本机图片";
            default -> "纯色";
        }, R.drawable.ic_sun, () -> {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("聊天背景")
                    .setItems(new String[]{"纯色", "主题渐变", "选择本机图片"}, (d, index) -> {
                        if (index == 2) pickWallpaper();
                        else setAppearance("background", index == 0 ? "plain" : "wash");
                    }).setNegativeButton("取消", null).create();
            showDialog(dialog, false);
        });
        if (Wallpaper.file(this).isFile()) {
            addRow(body, "移除本机背景图", "", R.drawable.ic_close, () -> {
                if (!Wallpaper.file(this).delete()) { notice("无法移除背景图"); return; }
                wallpaper = null;
                if (background.equals("image")) setAppearance("background", "plain");
                else render();
            });
        }
        space(body, 14);
        sectionTitle(body, "材质");
        String material = prefs.getString("material", "solid");
        addRow(body, "聊天材质", switch (material) {
            case "glass" -> "磨砂";
            case "flat" -> "扁平";
            default -> "实色";
        }, R.drawable.ic_sliders, () -> {
            String[] materials = {"solid", "flat", "glass"};
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("聊天材质")
                    .setItems(new String[]{"实色", "扁平", "磨砂"}, (d, i) -> setAppearance("material", materials[i]))
                    .setNegativeButton("取消", null).create();
            showDialog(dialog, false);
        });
        View preview = text("Serein", 17, ink, true);
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setBackground(shape(material.equals("flat") ? paper : tint, material.equals("flat") ? 8 : 24, line));
        body.addView(preview);
        space(body, 24);
        Switch reduce = new Switch(this);
        reduce.setText("减少动效");
        reduce.setTextSize(16);
        reduce.setTextColor(ink);
        reduce.setMinHeight(dp(56));
        reduce.setChecked(prefs.getBoolean("reduce_motion", false));
        reduce.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("reduce_motion", checked).apply());
        body.addView(reduce);
    }

    private void setAppearance(String name, String value) {
        prefs.edit().putString(name, value).apply();
        render();
    }

    private void editAccent() {
        LinearLayout form = dialogBody();
        EditText value = field(form, "#RRGGBB", prefs.getString("theme_accent", ""), false);
        saveForm("自定义主题色", form, false, () -> {
            String hex = value.getText().toString().strip();
            if (!hex.matches("#?[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("请输入六位十六进制颜色。");
            setAppearance("theme_accent", (hex.startsWith("#") ? hex : "#" + hex).toUpperCase(Locale.ROOT));
        }, null);
    }

    private void pickWallpaper() {
        Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        if (picker.resolveActivity(getPackageManager()) == null) {
            picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        }
        try { startActivityForResult(picker, PICK_BACKGROUND); }
        catch (android.content.ActivityNotFoundException error) { notice("找不到系统图片选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_BACKGROUND || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try {
                Wallpaper.importImage(this, uri);
                prefs.edit().putString("background", "image").apply();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    wallpaper = null;
                    render();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> { if (!isDestroyed()) notice("无法导入背景图"); });
            }
        });
    }

    private void drawBackground() {
        String background = prefs.getString("background", "plain");
        if (background.equals("wash")) {
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{blend(paper, accent, .15f), paper, tint}));
        }
        if (!background.equals("image")) return;
        if (wallpaper == null) wallpaper = BitmapFactory.decodeFile(Wallpaper.file(this).getAbsolutePath());
        if (wallpaper == null) return;
        ImageView image = new ImageView(this);
        image.setImageBitmap(wallpaper);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (prefs.getString("material", "solid").equals("glass")) {
            image.setRenderEffect(RenderEffect.createBlurEffect(dp(12), dp(12), Shader.TileMode.CLAMP));
        }
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
        View scrim = new View(this);
        scrim.setBackgroundColor((paper & 0x00ffffff) | 0xca000000);
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private static double linearChannel(int channel) {
        double s = channel / 255.0;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }

    private static int blend(int a, int b, float amount) {
        return Color.rgb(Math.round(Color.red(a) * (1 - amount) + Color.red(b) * amount),
                Math.round(Color.green(a) * (1 - amount) + Color.green(b) * amount),
                Math.round(Color.blue(a) * (1 - amount) + Color.blue(b) * amount));
    }

    private LinearLayout toolbar() {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.setMinimumHeight(dp(59));
        return row;
    }

    private void addRow(LinearLayout parent, String title, String subtitle, int glyph, Runnable tap) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(dp(54));
        button.setPadding(dp(6), dp(7), dp(5), dp(7));
        button.setBackground(ripple(0, 8));
        button.addView(icon(glyph, 21, ink));
        LinearLayout copy = column();
        copy.setPadding(dp(12), 0, dp(6), 0);
        copy.addView(text(title, 16, ink, true));
        if (!subtitle.isEmpty()) copy.addView(text(subtitle, 13, muted, false));
        button.addView(copy, new LinearLayout.LayoutParams(0, WRAP, 1));
        button.addView(icon(R.drawable.ic_chevron, 16, muted));
        button.setFocusable(true);
        button.setOnClickListener(v -> tap.run());
        Motion.press(button, this::motionEnabled);
        parent.addView(button);
    }

    private LinearLayout action(String label, int glyph, int endGlyph, int foreground, int background) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(ripple(background, 8));
        button.setFocusable(true);
        if (glyph != 0) button.addView(icon(glyph, 19, foreground));
        TextView text = text(label, 14, foreground, true);
        text.setGravity(Gravity.CENTER);
        if (glyph != 0) text.setPadding(dp(6), 0, 0, 0);
        button.addView(text);
        if (endGlyph != 0) button.addView(icon(endGlyph, 16, foreground));
        Motion.press(button, this::motionEnabled);
        return button;
    }

    private ImageButton iconButton(String label, int glyph, int foreground, int background, Runnable tap) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setImageResource(glyph);
        button.setImageTintList(ColorStateList.valueOf(foreground));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(ripple(background, 24));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setOnClickListener(v -> tap.run());
        Motion.press(button, this::motionEnabled);
        return button;
    }

    private ImageView icon(int glyph, int size, int foreground) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(glyph);
        icon.setImageTintList(ColorStateList.valueOf(foreground));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private ImageView logo(int size) {
        ImageView view = icon(R.drawable.serein_logo, size, accent);
        view.setImageTintList(null);
        return view;
    }

    private TextView text(String label, int size, int foreground, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(foreground);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private void sectionTitle(LinearLayout parent, String title) {
        TextView label = text(title, 13, muted, true);
        label.setAccessibilityHeading(true);
        label.setPadding(dp(6), dp(5), 0, dp(7));
        parent.addView(label);
    }

    private void space(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void rule(LinearLayout parent) {
        View rule = new View(this);
        rule.setBackgroundColor(line);
        parent.addView(rule, new LinearLayout.LayoutParams(MATCH, dp(1)));
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        return new LinearLayout(this);
    }

    private ScrollView scroll() {
        ScrollView view = new ScrollView(this);
        view.setClipToPadding(false);
        view.setVerticalScrollBarEnabled(false);
        return view;
    }

    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    private RippleDrawable ripple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x224E7B67), shape(fill, radius, 0), shape(Color.WHITE, radius, 0));
    }

    private void hideKeyboard() {
        getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        View focus = getCurrentFocus();
        if (focus != null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private static int color(String hex) {
        return Color.parseColor(hex);
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
