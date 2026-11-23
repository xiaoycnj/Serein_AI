package app.serein.chat;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityManager;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final String CHAT = "chat";
    private static final String SETTINGS = "settings";
    private static final String ACCOUNT = "account";
    private static final String SEARCH = "history-search";
    private static final String FILE_SPACE = "file-space";
    private static final String MEMORIES = "memories";
    private static final String APP_UPDATE = "app-update";
    private static final int MATCH = -1;
    private static final int WRAP = -2;
    private static final int MESSAGE_LIMIT = 4000;
    private static final int CREATE_EXPORT = 871;
    private static final String[] REASONING_MODES = {"none", "effort", "deepseek"};

    private record Section(String id, String title, String summary, int icon) {}
    private record TurnAnchors(View prompt, View answer) {}
    private record MenuItem(String label, Runnable action) {}
    private record SearchRequest(Providers.Provider provider, String query) {}
    private record LocalChatContext(List<ChatFile> attached, List<ChatFile> recent, List<ChatFile> documents,
                                    List<ChatFile> library, boolean memory, String imageScope) {
        boolean hasDocuments() { return !documents.isEmpty() || !library.isEmpty(); }
    }
    private record RetainedState(ChatArtifacts.Document document, GeneratedImage image, String draft,
                                 List<ChatFile> attachments, FileSpace files, ChatMemory memory, AppUpdate update) {}

    private static final Section[] SECTIONS = {
            new Section("providers", "服务商连接", "模型与 API Key", R.drawable.ic_link),
            new Section("appearance", "外观", "主题、壁纸与排版", R.drawable.ic_sun),
            new Section("plugins", "自定义插件", "联网搜索、生图与上下文", R.drawable.ic_puzzle),
            new Section("about", "关于", "", R.drawable.ic_info),
            new Section("policy", "相关政策", "本机数据", R.drawable.ic_shield),
            new Section("licenses", "开源声明", "依赖与素材", R.drawable.ic_file),
    };

    private SharedPreferences prefs;
    private ChatStore store;
    private AccountProfile profile;
    private Vault vault;
    private Providers providers;
    private ModelCatalog modelCatalog;
    private ImageLibrary images;
    private FileSpace files;
    private ChatMemory memory;
    private AppUpdate appUpdate;
    private boolean updateRunning;
    private LinearLayout updateBody;
    private CircularProgressIndicator updateRing;
    private TextView updateBytes;
    private AppUpdate.Phase visibleUpdatePhase;
    private String providerPage;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final ExecutorService localWork = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private View composerAnchor;
    private Snackbar activeNotice;
    private final List<Dialog> openDialogs = new ArrayList<>();
    private FrameLayout drawer;
    private View drawerRail;
    private final DrawerSwipe drawerSwipe = new DrawerSwipe();
    private final int[] swipeLocation = new int[2];
    private View swipeArea;
    private int systemSwipeLeft, systemSwipeRight;
    private LinearLayout screen;
    private View drawerTrigger;
    private boolean drawerArchived;
    private Runnable queuedHistorySearch;
    private Future<?> runningHistorySearch;
    private int historyQueryGeneration;
    private TranscriptScroll chatScroll;
    private final LinkedHashMap<Integer, TurnAnchors> turnAnchors = new LinkedHashMap<>();
    private View directoryButton;
    private View latestButton;
    private EditText input;
    private View reasoningRow;
    private MaterialButton sendButton;
    private MaterialButton attachmentButton;
    private LinearLayout attachmentTray;
    private List<ChatFile> draftFiles = List.of();
    private boolean importingFile;
    private boolean fileSpaceFromComposer;
    private boolean imeVisible;
    private boolean sending;
    private String pendingPrompt;
    private String replyStatus;
    private ReplyRetry retryReply;
    private StreamReply streamReply;
    private ReplyText liveReply;
    private TurnAnchors liveAnchors;
    private boolean liveHasArtifacts;
    private TextView liveStatus;
    private LinearLayout liveActions;
    private LinearLayout liveMedia;
    private String liveSource;
    private Bitmap wallpaper;
    private Typeface importedFont;
    private boolean importingAppearance;
    private ScrollView appearanceScroll;
    private Runnable updateAppearancePreview;
    private ChatArtifacts.Document pendingExport;
    private GeneratedImage pendingImageExport;
    private static final int CREATE_IMAGE_EXPORT = 873;
    private static final int PICK_BACKGROUND = 870;
    private static final int PICK_CHAT_FONT = 872;
    private static final int PICK_ATTACHMENT = 874;
    private static final int PICK_LIBRARY_FILE = 875;
    private static final int INSTALL_UPDATE = 876;
    private String page = CHAT;
    private String draft = "";
    private boolean backRegistered;
    private final OnBackInvokedCallback backCallback = this::navigateBack;

    private int paper, panel, ink, muted, line, tint, accent, onAccent;
    private Context controls;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getLastNonConfigurationInstance() instanceof RetainedState state) {
            pendingExport = state.document();
            pendingImageExport = state.image();
            draft = state.draft();
            draftFiles = state.attachments();
            files = state.files();
            memory = state.memory();
            appUpdate = state.update();
        }
        prefs = getSharedPreferences("serein", MODE_PRIVATE);
        setRecentsScreenshotEnabled(false);
        vault = new Vault(prefs);
        try {
            store = new ChatStore(prefs, vault);
            String savedProfile = vault.read("profile", null);
            profile = AccountProfile.read(savedProfile, prefs.getString("profile_name", ""));
            if (savedProfile == null && prefs.contains("profile_name")) vault.write("profile", profile.encode(), "profile_name");
            modelCatalog = ModelCatalog.load(this);
            providers = new Providers(vault, modelCatalog);
            images = new ImageLibrary(this);
            if (files == null) files = new FileSpace(this, vault);
            if (memory == null) memory = new ChatMemory(vault);
        } catch (RuntimeException error) {
            showDialog(dialogBuilder().setTitle("无法读取本机数据")
                    .setMessage("已保留原有文件。请重新启动 Serein 后再试。")
                    .setPositiveButton("关闭", (dialog, item) -> finish()).setCancelable(false).create(), true);
            return;
        }
        var referenced = imageReferences();
        var fileIds = fileReferences();
        long orphanCutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        localWork.execute(() -> {
            images.prune(referenced, orphanCutoff);
            files.prune(fileIds, orphanCutoff);
        });
        if (appUpdate == null) appUpdate = new AppUpdate(this);
        appUpdate.observe(this::onAppUpdate);
        if (saved != null) {
            page = saved.getString("page", CHAT);
            providerPage = saved.getString("providerPage");
            fileSpaceFromComposer = saved.getBoolean("fileSpaceFromComposer");
            if (page.equals("provider") && providers.get(providerPage) == null) page = "providers";
        }
        getWindow().setDecorFitsSystemWindows(false);
        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            var gestures = insets.getInsets(WindowInsets.Type.systemGestures());
            systemSwipeLeft = gestures.left;
            systemSwipeRight = gestures.right;
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
        if (page.equals(APP_UPDATE) && appUpdate.current().phase() == AppUpdate.Phase.IDLE) appUpdate.check();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            drawerSwipe.reset();
            swipeArea = drawerGestureArea(event);
            if (swipeArea != null) drawerSwipe.start(drawer == null ? DrawerSwipe.Direction.OPEN : DrawerSwipe.Direction.CLOSE,
                    event.getRawX(), event.getRawY(), event.getEventTime());
        } else if (drawerSwipe.tracking() || drawerSwipe.captured()) {
            if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL
                    || swipeArea != (drawer == null ? chatScroll : drawerRail)) drawerSwipe.cancel();
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                float slop = ViewConfiguration.get(this).getScaledTouchSlop();
                float distance = Math.max(dp(52), 3 * slop);
                if (drawerSwipe.move(event.getRawX(), event.getRawY(), event.getEventTime(), slop,
                        distance, ViewConfiguration.getLongPressTimeout())) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    try {
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                    } finally { cancel.recycle(); }
                }
                if (action == MotionEvent.ACTION_UP) {
                    boolean handled = drawerSwipe.captured();
                    var direction = drawerSwipe.finish(event.getRawX(), event.getRawY(), distance);
                    swipeArea = null;
                    if (direction == DrawerSwipe.Direction.OPEN) openDrawer();
                    if (direction == DrawerSwipe.Direction.CLOSE) closeDrawer();
                    if (handled) return true;
                }
            }
            if (drawerSwipe.captured()) {
                if (action == MotionEvent.ACTION_CANCEL) { drawerSwipe.reset(); swipeArea = null; }
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private View drawerGestureArea(MotionEvent event) {
        if (root == null || !page.equals(CHAT) || imeVisible || !prefs.getBoolean("drawer_swipe", true)) return null;
        var accessibility = getSystemService(AccessibilityManager.class);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) return null;
        View area = drawer == null ? chatScroll : drawerRail;
        if (area == null || !area.isShown()) return null;
        root.getLocationOnScreen(swipeLocation);
        int rootLeft = swipeLocation[0];
        int rootWidth = root.getWidth();
        area.getLocationOnScreen(swipeLocation);
        float x = event.getRawX() - rootLeft;
        float y = event.getRawY() - swipeLocation[1];
        float left = swipeLocation[0] - rootLeft;
        float right = Math.min(left + area.getWidth(), rootWidth - systemSwipeRight);
        if (drawer == null) right = Math.min(right, Math.max(left, systemSwipeLeft) + dp(84));
        return y >= 0 && y < area.getHeight() && DrawerSwipe.startsBetween(x, left, right, systemSwipeLeft)
                ? area : null;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page);
        out.putString("providerPage", providerPage);
        out.putBoolean("fileSpaceFromComposer", fileSpaceFromComposer);
    }

    @Override protected void onDestroy() {
        if (activeNotice != null) activeNotice.dismiss();
        for (Dialog dialog : List.copyOf(openDialogs)) if (dialog.isShowing()) dialog.dismiss();
        openDialogs.clear();
        if (streamReply != null) cancelStream(streamReply);
        if (appUpdate != null) {
            appUpdate.observe(null);
            if (!isChangingConfigurations()) appUpdate.close();
        }
        cancelHistorySearch();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        localWork.shutdown();
        if (backRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        super.onDestroy();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        String retainedDraft = streamReply != null && streamReply.fromComposer ? pendingPrompt : draft;
        List<ChatFile> retainedFiles = streamReply != null && streamReply.fromComposer ? streamReply.attachments : draftFiles;
        return new RetainedState(pendingExport, pendingImageExport, retainedDraft, retainedFiles, files, memory, appUpdate);
    }

    private void palette() {
        String theme = prefs.getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = theme.equals("dark") || (theme.equals("system") && systemDark);
        Configuration night = new Configuration();
        night.uiMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        controls = new ContextThemeWrapper(this, R.style.SereinTheme);
        ((ContextThemeWrapper) controls).applyOverrideConfiguration(night);
        var palette = Appearance.palette(dark, appearancePreset(),
                prefs.getString("theme_base", ""), prefs.getString("theme_accent", ""));
        paper = palette.paper();
        panel = palette.panel();
        ink = palette.ink();
        muted = palette.muted();
        line = palette.line();
        tint = palette.tint();
        accent = palette.accent();
        onAccent = palette.onAccent();
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
        getWindow().setNavigationBarContrastEnforced(false);
    }

    private void render() {
        int appearancePosition = page.equals("appearance") && appearanceScroll != null ? appearanceScroll.getScrollY() : 0;
        appearanceScroll = null;
        updateBody = null;
        updateRing = null;
        updateBytes = null;
        visibleUpdatePhase = null;
        updateAppearancePreview = null;
        if (activeNotice != null) activeNotice.dismiss();
        cancelHistorySearch();
        palette();
        root.removeAllViews();
        root.setBackgroundColor(paper);
        if (page.equals(CHAT)) setChatBackground(root);
        screen = column();
        screen.setFocusableInTouchMode(true);
        root.addView(screen, new FrameLayout.LayoutParams(MATCH, MATCH));
        drawer = null;
        drawerRail = null;
        input = null;
        composerAnchor = null;
        reasoningRow = null;
        sendButton = null;
        attachmentButton = null;
        attachmentTray = null;
        chatScroll = null;
        turnAnchors.clear();
        latestButton = null;
        directoryButton = null;
        liveReply = null;
        liveAnchors = null;
        liveStatus = null;
        liveActions = null;
        liveMedia = null;
        liveSource = null;
        if (page.equals(CHAT)) renderChat();
        else renderPage();
        screen.requestFocus();
        if (appearanceScroll != null && appearancePosition > 0) {
            ScrollView target = appearanceScroll;
            target.post(() -> target.scrollTo(0, appearancePosition));
        }
        updateBackCallback();
    }

    private void renderChat() {
        LinearLayout toolbar = toolbar();
        if (visibleWallpaper() || glassMaterial()) toolbar.setBackgroundColor(chatSurface(paper, muted));
        MaterialButton history = action("会话", R.drawable.ic_sidebar_menu, 0, ink, 0);
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
        TextView mode = text(choice == null ? "离线演示" : choice.provider().label + (autoRoute() ? " · 自动路由" : ""), 11, muted, false);
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
        directoryButton = iconButton("消息目录", R.drawable.ic_list, ink, 0, this::showMessageDirectory);
        directoryButton.setVisibility(View.GONE);
        toolbar.addView(directoryButton);
        toolbar.addView(iconButton("新建聊天", R.drawable.ic_plus, ink, 0, this::newChat));
        screen.addView(toolbar);

        FrameLayout transcriptArea = new FrameLayout(this);
        chatScroll = new TranscriptScroll();
        chatScroll.setFillViewport(true);
        transcriptArea.addView(chatScroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        latestButton = iconButton("回到最新", R.drawable.ic_latest, ink, tint, () -> chatScroll.toLatest());
        latestButton.setVisibility(View.GONE);
        var latestPosition = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.END);
        latestPosition.setMarginEnd(dp(12));
        latestPosition.bottomMargin = dp(8);
        transcriptArea.addView(latestButton, latestPosition);
        screen.addView(transcriptArea, new LinearLayout.LayoutParams(MATCH, 0, 1));
        renderMessages();

        LinearLayout composer = row();
        composerAnchor = composer;
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(3), dp(3), dp(4), dp(3));
        int fill = composerFill();
        int foreground = chatInk(fill);
        int surface = chatSurface(fill, foreground);
        int hint = Appearance.readable(muted, foreground, fill);
        if ((surface >>> 24) < 255) hint = foreground;
        composer.setBackground(shape(surface, composerRadius(), line));
        attachmentButton = iconButton("添加附件", R.drawable.ic_paperclip, foreground, 0, this::attachmentMenu);
        composer.addView(attachmentButton);
        input = UiKit.inlineField(controls);
        input.setHint("发消息");
        input.setTextColor(foreground);
        input.setHintTextColor(hint);
        input.setTextSize(chatTextSize());
        input.setMinHeight(dp(48));
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setTypeface(chatFont());
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setBackground(null);
        input.setPadding(dp(4), dp(9), dp(6), dp(9));
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(MESSAGE_LIMIT)});
        input.setText(draft);
        input.setEnabled(!sending);
        composer.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1));

        MaterialButton send = iconButton("发送", R.drawable.ic_send, onAccent, accent, () -> {
            if (streamReply != null) stopReply();
            else send();
        });
        sendButton = send;
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
        var trayScroll = new HorizontalScrollView(this);
        trayScroll.setHorizontalScrollBarEnabled(false);
        trayScroll.setFillViewport(false);
        attachmentTray = row();
        attachmentTray.setPadding(dp(16), 0, dp(16), 0);
        trayScroll.addView(attachmentTray, new FrameLayout.LayoutParams(WRAP, WRAP));
        screen.addView(trayScroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        screen.addView(composer, composerLayout);
        renderDraftFiles();
        updateReasoning(false);
    }

    private void updateSend(MaterialButton button) {
        boolean ready = sending || !importingFile && (!draft.isBlank() || !draftFiles.isEmpty());
        String label = sending ? "停止生成" : "发送";
        if (!label.contentEquals(button.getContentDescription())) {
            button.setIconResource(sending ? R.drawable.ic_stop : R.drawable.ic_send);
            button.setIconTint(ColorStateList.valueOf(onAccent));
            button.setContentDescription(label);
            button.setTooltipText(label);
        }
        button.setEnabled(ready);
        button.setAlpha(ready ? 1f : .44f);
        if (attachmentButton != null) attachmentButton.setEnabled(!sending && !importingFile);
    }

    private boolean motionEnabled() {
        return !prefs.getBoolean("reduce_motion", false) && ValueAnimator.areAnimatorsEnabled();
    }

    private void renderDraftFiles() {
        if (attachmentTray == null) return;
        attachmentTray.removeAllViews();
        ((View) attachmentTray.getParent()).setVisibility(draftFiles.isEmpty() ? View.GONE : View.VISIBLE);
        for (ChatFile file : draftFiles) {
            LinearLayout chip = row();
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(shape(panel, 8, line));
            var open = action(file.name(), file.image() ? R.drawable.ic_image : R.drawable.ic_file, 0, ink, 0);
            open.setMaxWidth(dp(170));
            open.setSingleLine(true);
            open.setEllipsize(TextUtils.TruncateAt.END);
            open.setOnClickListener(v -> previewFile(file));
            chip.addView(open, new LinearLayout.LayoutParams(WRAP, WRAP));
            chip.addView(iconButton("移除附件 " + file.name(), R.drawable.ic_close, muted, 0, () -> {
                var next = new ArrayList<>(draftFiles);
                next.remove(file);
                draftFiles = List.copyOf(next);
                if (retryReply != null && retryReply.attachments().contains(file)) {
                    clearReplyNotice();
                    renderMessages(chatScroll.following);
                }
                removeUnusedFiles(new java.util.HashSet<>(java.util.Set.of(file.id())));
                renderDraftFiles();
                updateSend(sendButton);
            }));
            var params = new LinearLayout.LayoutParams(WRAP, WRAP);
            params.setMarginEnd(dp(8));
            attachmentTray.addView(chip, params);
        }
    }

    private boolean canAttach() {
        if (sending || importingFile) return false;
        if (draftFiles.size() < ChatFile.MAX_ATTACHMENTS) return true;
        notice("每条消息最多添加 " + ChatFile.MAX_ATTACHMENTS + " 个附件");
        return false;
    }

    private void attachmentMenu() {
        if (!canAttach()) return;
        if (files.library().isEmpty()) { pickFile(false); return; }
        showMenu("添加附件", List.of(new MenuItem("从设备选择", () -> pickFile(false)),
                new MenuItem("从资料库选择", () -> openFileSpace(true))));
    }

    private void openFileSpace(boolean fromComposer) {
        hideKeyboard();
        fileSpaceFromComposer = fromComposer;
        go(FILE_SPACE);
    }

    private void pickFile(boolean library) {
        if (importingFile || (!library && !canAttach())) return;
        hideKeyboard();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(picker, library ? PICK_LIBRARY_FILE : PICK_ATTACHMENT); }
        catch (android.content.ActivityNotFoundException | SecurityException error) { notice("找不到系统文件选择器"); }
    }

    @FunctionalInterface private interface FileImport { ChatFile read() throws Exception; }

    private void importFile(boolean library, FileImport source, boolean returnToChat) {
        if (importingFile || (!library && !canAttach())) return;
        importingFile = true;
        if (sendButton != null) updateSend(sendButton);
        if (page.equals(FILE_SPACE)) render();
        notice(library ? "正在导入资料…" : "正在读取附件…");
        localWork.execute(() -> {
            ChatFile item = null;
            String errorMessage = null;
            try { item = source.read(); }
            catch (FileText.Rejected error) { errorMessage = error.getMessage(); }
            catch (Exception error) { errorMessage = "无法读取所选文件。"; }
            ChatFile imported = item;
            String problem = errorMessage;
            ui.post(() -> {
                importingFile = false;
                if (isDestroyed() || isFinishing()) {
                    if (imported != null && !library) files.removeOrphan(imported.id());
                    return;
                }
                if (imported != null && !library) {
                    var next = new ArrayList<>(draftFiles);
                    next.add(imported);
                    draftFiles = List.copyOf(next);
                }
                if (returnToChat && imported != null && page.equals(FILE_SPACE)) go(CHAT);
                else if (page.equals(FILE_SPACE)) render();
                if (sendButton != null) {
                    renderDraftFiles();
                    updateSend(sendButton);
                }
                if (imported == null) notice(problem == null ? "无法导入文件。" : problem);
                else if (library) notice("已导入资料库");
            });
        });
    }

    private void previewFile(ChatFile file) { previewFile(file, false); }

    private void previewFile(ChatFile file, boolean library) {
        View owner = screen;
        localWork.execute(() -> {
            try {
                Object content = file.image() ? files.previewImage(file) : files.text(file);
                ui.post(() -> {
                    if (isDestroyed() || isFinishing() || screen != owner) return;
                    ScrollView scroller = scroll();
                    LinearLayout body = dialogBody();
                    body.addView(text(fileSize(file), 12, muted, false));
                    space(body, 10);
                    if (content instanceof Bitmap bitmap) {
                        ImageView picture = new ImageView(this);
                        picture.setImageBitmap(bitmap);
                        picture.setAdjustViewBounds(true);
                        picture.setContentDescription(file.name());
                        body.addView(picture, new LinearLayout.LayoutParams(MATCH, WRAP));
                    } else {
                        TextView preview = text((String) content, 15, ink, false);
                        preview.setTextIsSelectable(true);
                        body.addView(preview);
                    }
                    scroller.addView(body);
                    var builder = dialogBuilder().setTitle(file.name()).setView(scroller).setNegativeButton("关闭", null);
                    if (library && !sending && !importingFile) {
                        builder.setPositiveButton("附加到聊天", (d, i) -> importFile(false, () -> files.copyToChat(file), true));
                    }
                    showDialog(builder.create(), true);
                });
            } catch (Exception error) {
                ui.post(() -> { if (screen == owner) notice("文件已移除或无法读取"); });
            }
        });
    }

    private String fileSize(ChatFile file) {
        return android.text.format.Formatter.formatShortFileSize(this, file.bytes());
    }

    private String[] effortLabels(Providers.Choice choice) {
        if (choice == null || choice.provider().anthropicMessages() || choice.model().reasoning().equals("none")) {
            return new String[]{"默认"};
        }
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
        Slider slider = UiKit.slider(controls, controlAccent(), effort(choice), labels.length - 1);
        slider.setEnabled(labels.length > 1);
        slider.setContentDescription("思考强度");
        slider.setStateDescription(labels.length == 1 ? "由模型决定" : labels[effort(choice)]);
        slider.setLabelFormatter(value -> labels[Math.min(labels.length - 1, Math.round(value))]);
        slider.addOnChangeListener((bar, value, user) -> {
            int progress = Math.round(value);
            if (!user || choice == null || progress >= labels.length) return;
            prefs.edit().putInt(effortKey(choice), progress).apply();
            title.setText("思考强度 · " + labels[progress]);
            bar.setStateDescription(labels[progress]);
        });
        if (labels.length > 1) container.addView(slider);
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
        renderMessages(true);
    }

    private void renderMessages(boolean followLatest) {
        int previousY = chatScroll.getScrollY();
        turnAnchors.clear();
        directoryButton.setVisibility(View.GONE);
        liveReply = null;
        liveAnchors = null;
        liveHasArtifacts = false;
        liveStatus = null;
        liveActions = null;
        liveMedia = null;
        liveSource = null;
        chatScroll.restorePosition(followLatest, previousY);
        chatScroll.removeAllViews();
        if (store.active == null && pendingPrompt == null && replyStatus == null) {
            LinearLayout welcome = column();
            welcome.setGravity(Gravity.CENTER);
            welcome.setPadding(dp(24), dp(24), dp(24), dp(34));
            welcome.addView(logo(78));
            TextView prompt = text("今天想聊什么？", 24, ink, true);
            prompt.setGravity(Gravity.CENTER);
            protectOnWallpaper(prompt);
            space(welcome, 14);
            welcome.addView(prompt);
            space(welcome, 18);
            View sample = action("帮我理顺今天的安排", 0, 0, Appearance.readable(accent, ink, tint), tint);
            sample.setOnClickListener(v -> {
                input.setText("帮我理顺今天的安排");
                input.setSelection(input.length());
            });
            welcome.addView(sample, new LinearLayout.LayoutParams(WRAP, WRAP));
            if (providers.choice() == null) {
                View demo = action("试试交互清单", 0, 0, ink, visibleWallpaper() ? paper : 0);
                demo.setOnClickListener(v -> submit("试试交互清单", -1, false));
                welcome.addView(demo);
            }
            chatScroll.addView(welcome, new ScrollView.LayoutParams(MATCH, WRAP));
            return;
        }

        LinearLayout transcript = column();
        transcript.setPadding(dp(18), dp(16), dp(18), dp(12));
        ReplyRetry attempt = retryReply != null && retryReply.matches(store.active) ? retryReply : null;
        if (store.active != null) {
            ChatHistory history = store.active.history;
            for (int id : history.path(streamReply == null ? history.leaf() : streamReply.parent)) {
                ChatHistory.Turn turn = history.turn(id);
                View question = userMessage(transcript, turn.prompt(), turn.attachments());
                space(transcript, 15);
                LinearLayout answer = column();
                transcript.addView(answer, new LinearLayout.LayoutParams(MATCH, WRAP));
                turnAnchors.put(id, new TurnAnchors(question, answer));
                ReplyText reply = replyView(answer, turn.model(), false);
                var markdown = markdown(turn.reply());
                reply.update(markdown.text(), false, dp(4));
                if (turn.reply().isBlank()) reply.setVisibility(View.GONE);
                if (turn.image() != null) addGeneratedImage(answer, turn.image());
                if (turn.card() != null) addCard(answer, store.active, id);
                addFileReferences(answer, turn.references());
                if (turn.replyState() != ChatHistory.ReplyState.COMPLETE) {
                    ReplyRetry savedReply = ReplyRetry.forTurn(store.active, id);
                    boolean retryFailed = savedReply.equals(attempt);
                    replyRecovery(answer, store.active, id, turn, retryFailed ? replyStatus : null);
                    if (retryFailed) attempt = null;
                }
                messageActions(answer, id, markdown.hasArtifacts());
                space(transcript, 8);
            }
        }
        if (pendingPrompt != null) {
            View question = userMessage(transcript, pendingPrompt, streamReply.attachments);
            space(transcript, 15);
            LinearLayout answer = column();
            transcript.addView(answer, new LinearLayout.LayoutParams(MATCH, WRAP));
            liveReply = replyView(answer, streamReply.model, true);
            liveAnchors = new TurnAnchors(question, answer);
            liveReply.setVisibility(View.GONE);
        }
        if (replyStatus != null && (pendingPrompt != null || attempt != null || retryReply == null)) {
            TextView status = text(replyStatus, 14, muted, false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            status.setPadding(0, dp(6), 0, dp(8));
            protectOnWallpaper(status);
            transcript.addView(status);
            liveStatus = status;
        }
        if (pendingPrompt != null) {
            liveActions = column();
            transcript.addView(liveActions);
            liveMedia = column();
            liveActions.addView(liveMedia);
            renderToolMedia(streamReply, false);
        } else if (attempt != null) {
            ReplyRetry retry = attempt;
            addRetryButton(transcript, retry.searchQuery() == null ? "重试" : "重试搜索", () -> resend(retry));
        }
        chatScroll.addView(transcript, new ScrollView.LayoutParams(MATCH, WRAP));
        directoryButton.setVisibility(turnAnchors.isEmpty() ? View.GONE : View.VISIBLE);
        if (streamReply != null) showStream(streamReply, streamReply.snapshot(), true);
    }

    private void addGeneratedImage(LinearLayout parent, GeneratedImage image) {
        addGeneratedImage(parent, image, false);
    }

    private void renderToolMedia(StreamReply stream, boolean reveal) {
        if (streamReply != stream || liveMedia == null) return;
        liveMedia.removeAllViews();
        GeneratedImage image = stream.image;
        if (image != null) addGeneratedImage(liveMedia, image, reveal);
        else if (stream.pendingImageSize != null) {
            if (liveStatus != null) liveStatus.setVisibility(View.GONE);
            var placeholder = new ImagePlaceholder(this, panel, ink, accent, line, this::motionEnabled);
            liveMedia.addView(placeholder, inlineImageLayout(stream.pendingImageSize));
        }
    }

    private LinearLayout.LayoutParams inlineImageLayout(String size) {
        int width = Math.min(dp(280), Math.max(dp(1), getResources().getDisplayMetrics().widthPixels - dp(36)));
        int height = switch (size) {
            case "1024x1536" -> Math.round(width * 1.5f);
            case "1536x1024" -> Math.round(width / 1.5f);
            default -> width;
        };
        var params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        return params;
    }

    private void addGeneratedImage(LinearLayout parent, GeneratedImage image, boolean reveal) {
        var surface = UiKit.readingSurface(controls, panel, line);
        FrameLayout box = new FrameLayout(this);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("查看生成的图片");
        box.addView(preview, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView status = text("正在读取图片…", 14, muted, false);
        status.setGravity(Gravity.CENTER);
        box.addView(status, new FrameLayout.LayoutParams(MATCH, MATCH));
        surface.addView(box, new FrameLayout.LayoutParams(MATCH, MATCH));
        parent.addView(surface, inlineImageLayout(image.size()));
        localWork.execute(() -> {
            try {
                Bitmap bitmap = images.preview(image);
                ui.post(() -> {
                    if (isDestroyed() || !preview.isAttachedToWindow()) return;
                    status.setVisibility(View.GONE);
                    preview.setImageBitmap(bitmap);
                    if (reveal) Motion.revealImage(preview, dp(12), motionEnabled());
                    preview.setOnClickListener(v -> showGeneratedImage(image, bitmap));
                });
            } catch (Exception error) {
                ui.post(() -> { if (status.isAttachedToWindow()) status.setText("无法读取本机图片"); });
            }
        });
    }

    private void showGeneratedImage(GeneratedImage image, Bitmap bitmap) {
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(520));
        preview.setImageBitmap(bitmap);
        preview.setContentDescription("生成的图片");
        showDialog(dialogBuilder().setTitle("生成的图片").setView(preview)
                .setNegativeButton("关闭", null).setPositiveButton("保存图片", (d, i) -> chooseImageExport(image))
                .create(), true);
    }

    private void addCard(LinearLayout parent, ChatStore.Chat chat, int id) {
        ChatHistory.Turn turn = chat.history.turn(id);
        View card = IuiView.create(controls, turn.card(), turn.cardState(),
                new IuiView.Colors(panel, ink, muted, line, tint, controlAccent()),
                (key, value) -> {
                    try { store.updateCard(chat, id, key, value); return true; }
                    catch (IllegalStateException error) { notice("无法保存卡片选择"); return false; }
                }, () -> {
                    if (sending || input == null || store.active != chat) return;
                    ChatHistory.Turn current = chat.history.turn(id);
                    String answer = current.card().draft(current.cardState());
                    if (answer.isBlank()) { notice("请先选择一项"); return; }
                    String combined = draft.isBlank() ? answer : draft + "\n\n" + answer;
                    if (combined.length() > MESSAGE_LIMIT) { notice("草稿空间不足，未加入卡片内容"); return; }
                    input.setText(combined);
                    input.setSelection(input.length());
                    notice("已加入草稿");
                });
        var params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        parent.addView(card, params);
    }

    private ReplyText replyView(LinearLayout transcript, String model, boolean live) {
        LinearLayout byline = row();
        byline.setGravity(Gravity.CENTER_VERTICAL);
        byline.addView(logo(28));
        TextView name = text(model, 13, ink, true);
        name.setPadding(dp(6), 0, 0, 0);
        protectOnWallpaper(name);
        byline.addView(name);
        transcript.addView(byline);
        ReplyText reply = new ReplyText(this);
        int foreground = chatInk(paper);
        reply.setTextColor(foreground);
        reply.setTextSize(chatTextSize());
        reply.setTypeface(chatFont());
        reply.setTextIsSelectable(!live);
        if (!live) reply.setMovementMethod(LinkMovementMethod.getInstance());
        reply.setLinkTextColor(Appearance.readable(accent, foreground, paper));
        reply.setLineSpacing(dp(4), 1);
        reply.setPadding(dp(8), dp(6), dp(8), dp(5));
        protectOnWallpaper(reply);
        transcript.addView(reply, new LinearLayout.LayoutParams(MATCH, WRAP));
        return reply;
    }

    private MarkdownText.Rendered markdown(String source) {
        return markdown(source, paper);
    }

    private MarkdownText.Rendered markdown(String source, int fill) {
        int foreground = chatInk(fill);
        int secondary = Appearance.readable(muted, foreground, fill);
        int code = Appearance.blend(fill, foreground, .08f);
        if (Appearance.contrast(foreground, code) < 4.5) code = fill;
        return MarkdownText.render(source, foreground, secondary, code, dp(9));
    }

    private void protectOnWallpaper(View content) {
        if (visibleWallpaper()) content.setBackground(shape(paper, 8, 0));
    }

    private void showStream(StreamReply stream, String source, boolean allowOpening) {
        if (streamReply != stream || liveReply == null || source.isBlank() || source.equals(liveSource)) return;
        var content = markdown(source);
        if (content.text().length() == 0) return;
        liveHasArtifacts = content.hasArtifacts();
        boolean animate = allowOpening && motionEnabled() && (!stream.presented || liveReply.hasOpening());
        stream.presented = true;
        liveSource = source;
        if (liveStatus != null && !stream.searching) liveStatus.setVisibility(View.GONE);
        liveReply.setVisibility(View.VISIBLE);
        liveReply.update(content.text(), animate, dp(2));
    }

    private final class TranscriptScroll extends ScrollView {
        private boolean following = true;
        private boolean layingOut;
        private int restoreY = -1;
        private View pendingAnchor;

        TranscriptScroll() {
            super(MainActivity.this);
            setVerticalScrollBarEnabled(false);
        }

        void restorePosition(boolean follow, int y) {
            pendingAnchor = null;
            following = follow;
            restoreY = y;
        }

        void toLatest() {
            pendingAnchor = null;
            following = true;
            if (getChildCount() > 0) scrollTo(0, getChildAt(0).getBottom());
            updateLatest();
        }

        void toAnchor(View target) {
            following = false;
            pendingAnchor = target;
            requestLayout();
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int y = restoreY >= 0 ? restoreY : getScrollY();
            layingOut = true;
            try {
                super.onLayout(changed, left, top, right, bottom);
                if (pendingAnchor != null && pendingAnchor.isAttachedToWindow()) {
                    Rect bounds = new Rect();
                    pendingAnchor.getDrawingRect(bounds);
                    offsetDescendantRectToMyCoords(pendingAnchor, bounds);
                    y = Math.max(0, bounds.top - dp(8));
                }
                if (getChildCount() > 0) scrollTo(0, following ? getChildAt(0).getBottom() : y);
            } finally {
                layingOut = false;
                restoreY = -1;
                pendingAnchor = null;
            }
            updateLatest();
        }

        @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            if (layingOut || restoreY >= 0 || y == oldY || getChildCount() == 0) return;
            following = y > oldY && !canScrollVertically(1);
            updateLatest();
        }

        private void updateLatest() {
            if (this == chatScroll && latestButton != null) {
                int visibility = !following && canScrollVertically(1) ? View.VISIBLE : View.GONE;
                if (latestButton.getVisibility() != visibility) latestButton.setVisibility(visibility);
            }
        }
    }

    private View messageBubble(LinearLayout transcript, String message) {
        int fill = messageFill();
        int foreground = chatInk(fill);
        TextView user = text("", chatTextSize(), foreground, false);
        user.setTypeface(chatFont());
        user.setTextIsSelectable(true);
        user.setText(markdown(message, fill).text(), TextView.BufferType.SPANNABLE);
        user.setMovementMethod(LinkMovementMethod.getInstance());
        user.setLinkTextColor(Appearance.readable(accent, foreground, fill));
        user.setLineSpacing(dp(4), 1);
        user.setPadding(dp(14), dp(10), dp(14), dp(10));
        user.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * .76));
        user.setBackground(shape(fill, messageRadius(), 0));
        LinearLayout.LayoutParams bubble = new LinearLayout.LayoutParams(WRAP, WRAP);
        bubble.gravity = Gravity.END;
        transcript.addView(user, bubble);
        return user;
    }

    private View userMessage(LinearLayout transcript, String message, List<ChatFile> attached) {
        if (attached.isEmpty()) return messageBubble(transcript, message);
        LinearLayout group = column();
        group.setGravity(Gravity.END);
        transcript.addView(group, new LinearLayout.LayoutParams(MATCH, WRAP));
        for (ChatFile file : attached) {
            var button = action(file.name(), file.image() ? R.drawable.ic_image : R.drawable.ic_file, 0, ink, tint);
            button.setMaxWidth(Math.min(dp(288), (int) (getResources().getDisplayMetrics().widthPixels * .76)));
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setOnClickListener(v -> previewFile(file));
            var layout = new LinearLayout.LayoutParams(WRAP, WRAP);
            layout.bottomMargin = dp(4);
            group.addView(button, layout);
        }
        if (!message.isBlank()) messageBubble(group, message);
        return group;
    }

    private void addFileReferences(LinearLayout answer, List<ChatFile> references) {
        if (references.isEmpty()) return;
        List<MenuItem> sources = new ArrayList<>();
        for (int i = 0; i < references.size(); i++) {
            ChatFile file = references.get(i);
            sources.add(new MenuItem("[F" + (i + 1) + "] " + file.name(), () -> previewFile(file)));
        }
        MaterialButton sourcesButton = action("资料引用 · " + references.size(), R.drawable.ic_file, 0, ink, tint);
        sourcesButton.setOnClickListener(v -> showMenu("资料引用", sources));
        answer.addView(sourcesButton, new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private void messageActions(LinearLayout transcript, int id, boolean hasArtifacts) {
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        protectOnWallpaper(actions);
        ChatStore.Chat chat = store.active;
        ChatHistory history = chat.history;
        ChatHistory.Turn turn = history.turn(id);
        if (!turn.reply().isBlank() || turn.card() != null) {
            actions.addView(iconButton("复制回复", R.drawable.ic_copy, muted, 0,
                    () -> copyText(turn.assistantText())));
        }
        actions.addView(iconButton("编辑提问", R.drawable.ic_pencil, muted, 0, () -> editPrompt(id)));
        if (turn.replyState() == ChatHistory.ReplyState.COMPLETE) {
            actions.addView(iconButton("重新生成", R.drawable.ic_refresh, muted, 0, () -> resendTurn(chat, id)));
        }
        actions.addView(iconButton("消息更多操作", R.drawable.ic_more, muted, 0,
                () -> showMessageMenu(chat, id, hasArtifacts)));
        List<Integer> versions = history.variants(id);
        if (versions.size() > 1) {
            int current = versions.indexOf(id);
            MaterialButton count = action((current + 1) + " / " + versions.size(), 0, 0, muted, 0);
            count.setMinWidth(0);
            count.setMinimumWidth(0);
            count.setContentDescription("切换版本，当前第 " + (current + 1) + " 个，共 " + versions.size() + " 个");
            count.setOnClickListener(v -> {
                if (sending) { notice("请先停止当前生成"); return; }
                String[] labels = new String[versions.size()];
                for (int i = 0; i < labels.length; i++) {
                    ChatHistory.Turn version = history.turn(versions.get(i));
                    labels[i] = (i + 1) + " · " + preview(version.promptLabel(), 32) + "\n" + preview(version.assistantText(), 56);
                }
                showDialog(dialogBuilder().setTitle("对话版本").setSingleChoiceItems(labels, current, (dialog, index) -> {
                    try {
                        store.selectVersion(versions.get(index));
                        clearReplyNotice();
                        renderMessages(false);
                        dialog.dismiss();
                    } catch (IllegalStateException error) { notice("无法保存版本选择"); }
                }).setNegativeButton("取消", null).create(), true);
            });
            actions.addView(count);
        }
        transcript.addView(actions);
    }

    private void copyText(String source) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("Serein", source);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        if (clipboard == null) { notice("剪贴板不可用"); return; }
        try { clipboard.setPrimaryClip(clip); }
        catch (RuntimeException error) { notice("内容太长或剪贴板不可用，可选中部分内容再复制"); }
    }

    private void showMessageDirectory() {
        ChatStore.Chat chat = store.active;
        if (chat == null || turnAnchors.isEmpty()) return;
        List<MenuItem> items = new ArrayList<>();
        int number = 1;
        for (int id : turnAnchors.keySet()) {
            String label = number++ + " · " + preview(chat.history.turn(id).promptLabel(), 82);
            items.add(new MenuItem(label, () -> jumpTo(chat, id, false)));
        }
        showMenu("消息目录", items);
    }

    private void jumpTo(ChatStore.Chat chat, int id, boolean answer) {
        if (store.active != chat || chatScroll == null) return;
        TurnAnchors anchors = turnAnchors.get(id);
        if (anchors != null) chatScroll.toAnchor(answer ? anchors.answer() : anchors.prompt());
    }

    private void showMessageMenu(ChatStore.Chat chat, int id, boolean hasArtifacts) {
        if (store.active != chat || !turnAnchors.containsKey(id)) return;
        ChatHistory.Turn turn = chat.history.turn(id);
        List<MenuItem> items = new ArrayList<>();
        if (!turn.reply().isBlank() || turn.card() != null) {
            items.add(new MenuItem("引用到草稿", () -> quoteReply(turn)));
        }
        items.add(new MenuItem("从此消息创建分支", () -> forkChat(chat, id)));
        if (hasArtifacts) items.add(new MenuItem("代码与表格", () -> openArtifacts(chat, id)));
        showMenu("消息操作", items);
    }

    private void showMenu(String title, List<MenuItem> items) {
        String[] labels = items.stream().map(MenuItem::label).toArray(String[]::new);
        AlertDialog dialog = dialogBuilder().setTitle(title).setAdapter(UiOverlays.menuRows(controls, labels, ink), (d, index) -> {
            d.dismiss();
            items.get(index).action().run();
        }).setNegativeButton("关闭", null).create();
        showDialog(dialog, true);
    }

    private void quoteReply(ChatHistory.Turn turn) {
        if (sending || input == null) { notice("请先停止当前生成"); return; }
        var quote = ChatArtifacts.quote(draft, turn.assistantText(), MESSAGE_LIMIT);
        if (quote == null) { notice("草稿空间不足，未加入引用"); return; }
        input.setText(quote.value());
        input.setSelection(input.length());
        input.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        if (quote.truncated()) notice("草稿空间有限，已引用部分回复");
    }

    private void forkChat(ChatStore.Chat chat, int id) {
        if (sending) { notice("请先停止当前生成"); return; }
        confirmLeavingDraft(() -> {
            try { store.fork(chat, id); }
            catch (IllegalStateException error) { notice("无法创建会话分支"); return; }
            discardDraft();
            go(CHAT);
        });
    }

    private void openArtifacts(ChatStore.Chat chat, int id) {
        if (store.active != chat || !turnAnchors.containsKey(id)) return;
        View owner = screen;
        String source = chat.history.turn(id).reply();
        localWork.execute(() -> {
            ChatArtifacts.Blocks blocks = ChatArtifacts.blocks(source);
            ui.post(() -> {
                if (isDestroyed() || screen != owner || store.active != chat || !turnAnchors.containsKey(id)) return;
                if (blocks.items().isEmpty()) { notice("这条回复没有代码块或表格"); return; }
                if (blocks.items().size() == 1) { showArtifact(blocks.items().get(0)); return; }
                List<MenuItem> items = new ArrayList<>();
                int number = 1;
                for (ChatArtifacts.Block block : blocks.items()) {
                    items.add(new MenuItem(number++ + " · " + ArtifactViewer.label(block), () -> showArtifact(block)));
                }
                showMenu(blocks.limited() ? "代码与表格 · 前 100 项" : "代码与表格", items);
            });
        });
    }

    private void showArtifact(ChatArtifacts.Block block) {
        ChatArtifacts.Document file;
        Runnable copy;
        if (block instanceof ChatArtifacts.CodeBlock code) {
            file = ChatArtifacts.codeFile(code);
            copy = () -> copyText(code.source());
        } else {
            var table = (ChatArtifacts.Table) block;
            file = ChatArtifacts.csvFile(table);
            copy = () -> localWork.execute(() -> {
                String content = ChatArtifacts.tableClipboard(table);
                ui.post(() -> { if (!isDestroyed()) copyText(content); });
            });
        }
        var colors = new ArtifactViewer.Colors(ink, muted, panel, line, tint, controlAccent());
        showDialog(ArtifactViewer.dialog(dialogBuilder(), controls, block, colors, copy, () -> chooseExport(file)), true);
    }

    private static String preview(String value, int length) {
        String flat = value.replace('\n', ' ').strip();
        return flat.codePointCount(0, flat.length()) <= length ? flat : flat.substring(0, flat.offsetByCodePoints(0, length)) + "…";
    }

    private void editPrompt(int id) {
        if (sending) { notice("请先停止当前生成"); return; }
        ChatHistory history = store.active.history;
        LinearLayout body = dialogBody();
        ChatHistory.Turn turn = history.turn(id);
        if (!turn.attachments().isEmpty()) {
            body.addView(text(turn.attachments().stream().map(ChatFile::name).collect(java.util.stream.Collectors.joining(" · ")),
                    13, muted, false));
            space(body, 8);
        }
        EditText edit = multilineField(body, "提问", turn.prompt(), 4000);
        ScrollView scroll = scroll();
        scroll.addView(body);
        AlertDialog dialog = dialogBuilder().setTitle("编辑提问").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton(turn.searchQuery() == null ? "发送" : "继续搜索", null).create();
        showDialog(dialog, true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = edit.getText().toString().strip();
            if (value.isEmpty() && turn.attachments().isEmpty()) { edit.setError("请输入提问"); return; }
            if (turn.searchQuery() == null) submit(value, history.parent(id), false, dialog::dismiss, null, turn.attachments());
            else {
                dialog.dismiss();
                showWebSearch(value, history.parent(id), false,
                        value.equals(turn.prompt()) ? turn.searchQuery() : WebSearch.suggestedQuery(value), turn.attachments());
            }
        });
    }

    private void send() {
        String message = draft.strip();
        if (sending || importingFile || (message.isBlank() && draftFiles.isEmpty())) return;
        submit(message, store.active == null ? -1 : store.active.history.leaf(), true);
    }

    private boolean autoRoute() { return prefs.getBoolean("auto_route", false); }

    private void configureImageTool() {
        var candidates = providers.all.stream().filter(provider -> !provider.anthropicMessages())
                .collect(java.util.stream.Collectors.toList());
        if (candidates.isEmpty()) {
            showDialog(dialogBuilder().setTitle("图片生成")
                    .setMessage("请先添加支持 OpenAI Images API 的服务商。")
                    .setNegativeButton("取消", null).setPositiveButton("服务商连接", (d, i) -> go("providers"))
                    .create(), true);
            return;
        }
        Providers.Choice selected = providers.choice();
        Providers.Provider preferred = providers.get(prefs.getString("image_provider", ""));
        if (preferred == null && selected != null) preferred = selected.provider();
        Providers.Provider[] provider = {candidates.contains(preferred) ? preferred : candidates.get(0)};
        LinearLayout body = dialogBody();
        MaterialButton providerButton = action(provider[0].label, R.drawable.ic_link, 0, ink, tint);
        body.addView(providerButton, new LinearLayout.LayoutParams(MATCH, WRAP));
        EditText model = field(body, "图片模型 ID", imageModelDefault(provider[0]), false);
        providerButton.setOnClickListener(v -> {
            String[] labels = candidates.stream().map(candidate -> candidate.label).toArray(String[]::new);
            chooseImageOption("图片服务商", labels, candidates.indexOf(provider[0]), index -> {
                provider[0] = candidates.get(index);
                providerButton.setText(provider[0].label);
                model.setText(imageModelDefault(provider[0]));
            });
        });
        String[] sizes = {"", "1024x1024", "1024x1536", "1536x1024"};
        String[] sizeLabels = {"服务商默认", "方形 1024×1024", "竖图 1024×1536", "横图 1536×1024"};
        int[] size = {Math.max(0, List.of(sizes).indexOf(prefs.getString("image_size", "")))};
        MaterialButton sizeButton = action("尺寸 · " + sizeLabels[size[0]], 0, 0, ink, tint);
        body.addView(sizeButton, new LinearLayout.LayoutParams(MATCH, WRAP));
        sizeButton.setOnClickListener(v -> chooseImageOption("图片尺寸", sizeLabels, size[0], index -> {
            size[0] = index;
            sizeButton.setText("尺寸 · " + sizeLabels[index]);
        }));
        saveForm("图片服务商", body, true, "保存", () -> {
            String imageModel = model.getText().toString().strip();
            if (imageModel.isBlank() || imageModel.length() > 256) {
                throw new IllegalArgumentException("请输入有效的图片模型 ID。");
            }
            prefs.edit().putString("image_provider", provider[0].id).putString("image_model_" + provider[0].id, imageModel)
                    .putString("image_size", sizes[size[0]]).apply();
            render();
        }, null);
    }

    private ImageGeneration.Request imageToolRequest() {
        var provider = providers.get(prefs.getString("image_provider", ""));
        if (provider == null || provider.anthropicMessages()) return null;
        String model = imageModelDefault(provider);
        if (model.isBlank()) return null;
        return new ImageGeneration.Request(provider, model, prefs.getString("image_size", ""));
    }

    private void chooseImageOption(String title, String[] labels, int selected, java.util.function.IntConsumer choose) {
        showDialog(dialogBuilder().setTitle(title).setSingleChoiceItems(labels, selected, (dialog, index) -> {
            choose.accept(index);
            dialog.dismiss();
        }).setNegativeButton("取消", null).create(), true);
    }

    private String imageModelDefault(Providers.Provider provider) {
        String last = prefs.getString("image_model_" + provider.id, "");
        if (!last.isBlank()) return last;
        for (Providers.Model model : provider.models) {
            if (model.id().toLowerCase(Locale.ROOT).matches(".*(?:gpt-image|dall-e|seedream|flux|imagen).*")) return model.id();
        }
        return "";
    }

    private java.util.Set<String> imageReferences() {
        var ids = new java.util.HashSet<String>();
        for (ChatStore.Chat chat : store.recent) chat.history.collectImageIds(ids);
        return ids;
    }

    private java.util.Set<String> fileReferences() {
        var ids = new java.util.HashSet<String>();
        for (ChatStore.Chat chat : store.recent) chat.history.collectFileIds(ids);
        for (ChatFile file : draftFiles) ids.add(file.id());
        if (retryReply != null) for (ChatFile file : retryReply.attachments()) ids.add(file.id());
        if (streamReply != null) for (ChatFile file : streamReply.attachments) ids.add(file.id());
        return ids;
    }

    private void removeUnusedFiles(java.util.Set<String> candidates) {
        candidates.removeAll(fileReferences());
        if (!candidates.isEmpty()) localWork.execute(() -> candidates.forEach(files::removeOrphan));
    }

    private Providers.Provider searchProvider(Providers.Choice choice) {
        if (choice != null && WebSearch.officialProvider(choice.provider().base) && !choice.provider().secret().isBlank()) {
            return choice.provider();
        }
        for (Providers.Provider provider : providers.all) {
            if (WebSearch.officialProvider(provider.base) && !provider.secret().isBlank()) return provider;
        }
        return null;
    }

    private void showWebSearch(String message, int parent, boolean fromComposer, String suggestedQuery, List<ChatFile> attached) {
        if (sending) return;
        Providers.Choice choice = providers.choice();
        Providers.Provider provider = searchProvider(choice);
        LinearLayout form = dialogBody();
        String detail = provider == null ? "配置官方 DeepSeek 服务商和 API Key 后即可在聊天中搜索。也可以用浏览器打开 Bing。"
                : "检索词会发送给 DeepSeek，搜索可能单独计费。" + (choice == null
                ? "本轮直接显示搜索来源和摘要。" : "结果摘要会发给当前聊天模型。")
                + "\n在 Bing 打开时，检索词会发送给 Bing。";
        form.addView(text(detail, 14, muted, false));
        space(form, 10);
        EditText query = field(form, "检索词", suggestedQuery, false);
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2 * WebSearch.QUERY_LIMIT)});
        ScrollView scroll = scroll();
        scroll.addView(form);
        AlertDialog dialog = dialogBuilder().setTitle("联网搜索").setView(scroll)
                .setNegativeButton("取消", null).setNeutralButton("在 Bing 打开", null)
                .setPositiveButton("搜索并发送", null).create();
        showDialog(dialog, true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(provider != null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String terms = WebSearch.checkedQuery(query.getText().toString());
                dialog.dismiss();
                submit(message.isBlank() ? terms : message, parent, fromComposer, () -> {}, new SearchRequest(provider, terms), attached);
            } catch (IllegalArgumentException error) { query.setError(error.getMessage()); }
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            try {
                String terms = WebSearch.checkedQuery(query.getText().toString());
                if (openBing(terms)) dialog.dismiss();
            } catch (IllegalArgumentException error) { query.setError(error.getMessage()); }
        });
    }

    private boolean openBing(String query) {
        try {
            var link = android.net.Uri.parse("https://www.bing.com/search");
            if (!query.isEmpty()) link = link.buildUpon().appendQueryParameter("q", query).build();
            startActivity(new Intent(Intent.ACTION_VIEW, link));
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            notice("无法打开浏览器");
            return false;
        }
    }

    private void submit(String message, int parent, boolean fromComposer) {
        submit(message, parent, fromComposer, () -> {}, null, fromComposer ? draftFiles : List.of());
    }

    private void submit(String message, int parent, boolean fromComposer, Runnable accepted, SearchRequest search,
                        List<ChatFile> attached) {
        if (sending || importingFile) return;
        if (attached.stream().anyMatch(file -> !files.exists(file))) {
            notice("附件已移除，请重新添加。");
            return;
        }
        Providers.Choice choice = providers.choice();
        if (choice == null) {
            if (!attached.isEmpty()) { notice("请选择支持该附件类型的在线模型"); return; }
            if (search != null) {
                accepted.run();
                startReply(message, "DeepSeek Search", parent, fromComposer, "正在搜索网页…", search.query(), stream -> {
                    stream.sources = ModelApi.search(search.provider(), search.query(), stream.cancellation);
                });
                return;
            }
            if (message.equals("试试交互清单")) {
                accepted.run();
                startReply(message, "Serein", parent, fromComposer, "等待 Serein 回复…", stream ->
                        stream.card = ChatCard.parse("""
                                {"title":"今天的安排","blocks":[{"id":"plan","type":"checklist","items":[
                                  {"label":"选出今天最重要的一件事"},{"label":"留一段不被打扰的时间"},{"label":"给临时事项留出余量"}
                                ]}]}
                                """));
                return;
            }
            if (message.equalsIgnoreCase("Markdown")) {
                accepted.run();
                startReply(message, "Serein", parent, fromComposer, "等待 Serein 回复…", stream -> {
                    for (String part : new String[]{"# 阅读示例\n\n", "**重点**、*斜体*和~~删除线~~\n\n",
                            "> 这是一段引用。\n\n", "1. 列表项\n2. 行内代码 `val`\n\n",
                            "```kotlin\nval ready = true\n```\n\n", "| 格式 | 示例 |\n| --- | --- |\n| 链接 | [CommonMark](https://commonmark.org/) |"}) {
                        stream.cancellation.check();
                        stream.append(part);
                        Thread.sleep(350);
                    }
                });
                return;
            }
            try {
                store.append(parent, message, ChatStore.demoReply(message), "Serein", null);
                accepted.run();
                if (fromComposer) input.setText("");
                clearReplyNotice();
                renderMessages();
            } catch (IllegalStateException error) { notice("无法保存本机聊天记录"); }
            return;
        }
        ChatStore.Chat chat = store.active;
        List<Integer> path = chat == null ? List.of() : chat.history.path(parent);
        List<ChatHistory.Turn> history = new ArrayList<>(path.size());
        for (int id : path) history.add(chat.history.turn(id));
        String manualScope = chatScope(choice);
        List<ChatFile> chatFiles = ChatAttachments.available(history, attached, manualScope);
        List<ChatFile> documents = ChatAttachments.searchableDocuments(history, attached);
        List<ChatFile> library = prefs.getBoolean("chat_file_space", false) ? files.library() : List.of();
        boolean memoryEnabled = prefs.getBoolean("chat_memory", false);
        boolean hasFileContext = !chatFiles.isEmpty() || !documents.isEmpty();
        boolean documentTools = !library.isEmpty() || !documents.isEmpty();
        String account = profile.systemPrompt();
        String systemPrompt = hasFileContext || documentTools || memoryEnabled
                ? (account.isBlank() ? "" : account + "\n\n") + ChatReferences.INSTRUCTIONS : account;
        boolean compact = prefs.getBoolean("auto_compact", false);
        boolean cards = prefs.getBoolean("chat_cards", false);
        var availableSearch = search == null && chatFiles.isEmpty() && prefs.getBoolean("chat_web", false) ? searchProvider(choice) : null;
        var availableImage = search == null && chatFiles.isEmpty() && prefs.getBoolean("chat_image", false) ? imageToolRequest() : null;
        boolean tools = cards || availableSearch != null || availableImage != null || documentTools || memoryEnabled;
        Providers.Choice target = ChatAttachments.hasImages(chatFiles) ? choice
                : routeChoice(choice, message, history, systemPrompt, compact, tools, ChatAttachments.estimate(chatFiles));
        ModelCatalog.Entry entry = modelCatalog.find(target.model().id());
        boolean supportsTools = entry == null || entry.supportsTools();
        var web = supportsTools ? availableSearch : null;
        var image = supportsTools ? availableImage : null;
        var searchRecipient = search != null ? search.provider() : web;
        var local = new LocalChatContext(attached, chatFiles, documents, library, memoryEnabled,
                manualScope.equals(chatScope(target)) ? manualScope : "");
        var delivery = new ChatDelivery(ChatDelivery.Destination.chat(target.provider()),
                searchRecipient == null ? null : ChatDelivery.Destination.search(searchRecipient),
                image == null ? null : ChatDelivery.Destination.image(image.provider()), search != null,
                !chatFiles.isEmpty() || supportsTools && !documents.isEmpty(), supportsTools && !library.isEmpty(),
                supportsTools && memoryEnabled, !account.isEmpty(), compact);
        String consentKey = delivery.preferenceKey();
        Runnable send = () -> {
            accepted.run();
            sendOnline(target, message, parent, fromComposer, path, history,
                    chat == null ? null : chat.checkpoint, systemPrompt, compact, cards, search, web, image,
                    local, entry);
        };
        if (!prefs.getBoolean(consentKey, false)) {
            showDialog(dialogBuilder().setTitle("发送给 " + target.provider().label)
                    .setMessage(delivery.description())
                    .setNegativeButton("取消", null).setPositiveButton("发送", (dialog, item) -> {
                        prefs.edit().putBoolean(consentKey, true).apply();
                        send.run();
                    }).create(), true);
        } else send.run();
    }

    private Providers.Choice routeChoice(Providers.Choice manual, String message, List<ChatHistory.Turn> history,
                                         String account, boolean compact, boolean tools, int attachmentCost) {
        if (!autoRoute() || effort(manual) > 0) return manual;
        List<ChatContext.ModelSpec> candidates = new ArrayList<>();
        for (Providers.Model model : manual.provider().models) {
            ModelCatalog.Entry entry = modelCatalog.find(model.id());
            if (entry != null && !entry.routeDisabled()) {
                candidates.add(new ChatContext.ModelSpec(model.id(), entry.inputLimit(), entry.tier(), entry.price(),
                        entry.supportsTools()));
            }
        }
        ChatContext.ModelSpec selected = ChatContext.route(candidates, message,
                ChatContext.estimate(history, account, message) + attachmentCost,
                ChatContext.estimate(ChatContext.recent(history), account, message) + attachmentCost, compact, tools);
        if (selected != null) for (Providers.Model model : manual.provider().models) {
            if (model.id().equals(selected.id())) return new Providers.Choice(manual.provider(), model);
        }
        return manual;
    }

    private void sendOnline(Providers.Choice choice, String message, int parent, boolean fromComposer,
                            List<Integer> path, List<ChatHistory.Turn> history, ChatContext.Checkpoint checkpoint,
                            String account, boolean compact, boolean iui, SearchRequest search,
                            Providers.Provider web, ImageGeneration.Request image, LocalChatContext local, ModelCatalog.Entry entry) {
        List<ChatFile> attached = local.attached();
        List<ChatFile> chatFiles = local.recent();
        int level = effort(choice);
        int inputLimit = entry == null ? 0 : entry.inputLimit();
        int capacity = ChatContext.budget(inputLimit);
        int reserved = chatFiles.isEmpty() ? 0 : Math.min(ChatAttachments.estimate(chatFiles) + 600,
                Math.max(ChatAttachments.imageCost(attached) + 600, capacity / 3));
        boolean preparing = compact && ChatContext.estimate(history, account, message) + reserved > ChatContext.threshold(inputLimit);
        String model = choice.model().title();
        String scope = chatScope(choice);
        String waiting = "等待 " + model + " 回复…";
        boolean supportsTools = entry == null || entry.supportsTools();
        var tools = new ChatTools.Options(supportsTools && web != null, supportsTools && image != null,
                supportsTools && local.hasDocuments(), supportsTools && local.memory() && !memory.entries().isEmpty(),
                supportsTools && local.memory() && fromComposer && !message.isBlank());
        String initial = search != null ? "正在搜索网页…" : preparing ? "正在压缩较早的对话…" : waiting;
        String imageScope = ChatAttachments.hasImages(attached) ? scope : "";
        startReply(message, model, parent, fromComposer, initial, search == null ? null : search.query(), attached, imageScope, stream -> {
            String prompt = message;
            if (search != null) {
                stream.sources = ModelApi.search(search.provider(), search.query(), stream.cancellation);
                prompt = WebSearch.groundedPrompt(message, stream.sources);
            }
            long tokens = ChatContext.estimate(history, account, prompt) + reserved;
            boolean summarize = compact && tokens > ChatContext.threshold(inputLimit);
            if (search != null && !summarize && inputLimit > 0 && tokens > ChatContext.budget(inputLimit)) {
                throw new IOException("模型的上下文空间不足以容纳聊天和搜索资料，请选择上下文更大的模型。");
            }
            ChatContext.Context context = new ChatContext.Context(history, "", null);
            if (summarize) {
                if (search != null) updateReplyStatus(stream, "正在压缩较早的对话…");
                try {
                    context = ChatContext.prepare(history, path, account, prompt, inputLimit, scope, checkpoint, reserved,
                            (previous, chunk) -> ModelApi.summarize(choice, previous, chunk, stream.cancellation));
                } catch (Exception error) {
                    stream.cancellation.check();
                    if (inputLimit == 0 || tokens > ChatContext.budget(inputLimit)) throw error;
                    ui.post(() -> { if (streamReply == stream) notice("摘要生成失败，本轮使用完整历史"); });
                }
            }
            if (search != null || summarize) updateReplyStatus(stream, waiting);
            stream.cancellation.check();
            stream.checkpoint = context.checkpoint();
            ChatAttachments.Prepared payload = null;
            if (!chatFiles.isEmpty()) {
                long room = inputLimit == 0 && !compact ? capacity : capacity
                        - ChatContext.estimate(context.recent(), account, prompt) - ChatContext.estimate(context.summary())
                        - ChatAttachments.imageCost(chatFiles) - 600;
                if (inputLimit > 0 && room < 256 && !attached.isEmpty()) {
                    throw new IOException("模型的上下文空间不足以发送附件，请选择上下文更大的模型。");
                }
                payload = ChatAttachments.prepare(context.recent(), prompt, attached, files::read,
                        choice.provider().anthropicMessages(), (int) Math.max(0, room), local.imageScope(), stream.cancellation);
            }
            ChatReferences references = new ChatReferences(local.documents(), local.library(), file -> {
                if (local.documents().contains(file)) return files.text(file);
                if (!prefs.getBoolean("chat_file_space", false)) throw new java.io.FileNotFoundException();
                return files.libraryText(file);
            }, stream.cancellation);
            stream.references = references;
            stream.card = ModelApi.chat(choice, context.recent(), prompt, level, account, context.summary(),
                    iui && supportsTools, tools, (name, argument) -> runChatTool(stream, name, argument, web, image, waiting, references, message),
                    payload, stream.cancellation, stream::append);
        });
    }

    private static String chatScope(Providers.Choice choice) {
        return choice.provider().id + "\n" + choice.provider().base + "\n" + choice.model().id();
    }

    private String runChatTool(StreamReply stream, String name, String argument, Providers.Provider web,
                               ImageGeneration.Request request, String waiting, ChatReferences references, String message) throws Exception {
        stream.cancellation.check();
        switch (name) {
            case ChatTools.FIND_FILE, ChatTools.READ_FILE -> {
                updateReplyStatus(stream, "正在查找资料…");
                String result = ChatTools.FIND_FILE.equals(name) ? references.search(argument) : references.read(argument);
                updateReplyStatus(stream, waiting);
                return result;
            }
            case ChatTools.RECALL -> {
                var matches = new org.json.JSONArray();
                if (prefs.getBoolean("chat_memory", false)) {
                    for (ChatMemory.Entry entry : memory.search(argument)) matches.put(entry.text());
                }
                return new org.json.JSONObject().put("reference_data_only", true).put("matches", matches).toString();
            }
            case ChatTools.REMEMBER -> {
                if (!prefs.getBoolean("chat_memory", false)) return "Memory is disabled.";
                return switch (memory.assess(argument, message)) {
                    case SAVED -> {
                        stream.memoryNote = argument;
                        yield "Accepted for local saving once this reply is complete.";
                    }
                    case ALREADY_SAVED -> "This note is already saved.";
                    case UNSUITABLE -> "This text is not eligible for memory.";
                    case FULL -> "The local memory store is full.";
                };
            }
        }
        stream.toolAttempt = ChatTools.external(name);
        if (ChatTools.SEARCH.equals(name)) {
            stream.searching = true;
            updateReplyStatus(stream, "正在搜索网页…");
            var hits = ModelApi.search(web, argument, stream.cancellation);
            stream.sources = hits;
            stream.searching = false;
            updateReplyStatus(stream, waiting);
            return ChatTools.searchResult(hits);
        }
        if (!ChatTools.IMAGE.equals(name) || request == null) throw new IOException("模型请求了未启用的聊天工具。");
        stream.pendingImageSize = request.size();
        ui.post(() -> renderToolMedia(stream, false));
        byte[] bytes = ImageGeneration.generate(request, argument, stream.cancellation);
        GeneratedImage image = images.store(request, bytes);
        if (!stream.publishImage(image)) images.remove(image.id());
        ui.post(() -> renderToolMedia(stream, true));
        updateReplyStatus(stream, waiting);
        return "One image was generated and is displayed inline in the chat. Do not invent an image link or call another tool.";
    }

    private void updateReplyStatus(StreamReply stream, String status) {
        ui.post(() -> {
            if (streamReply != stream) return;
            replyStatus = status;
            if (liveStatus != null) {
                liveStatus.setText(status);
                liveStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    private interface ReplySource {
        void read(StreamReply stream) throws Exception;
    }

    private final class StreamReply {
        private static final long MIN_REFRESH_MS = 40;
        private static final long MAX_REFRESH_MS = 350;
        final String model;
        final int parent;
        final boolean fromComposer;
        final String searchQuery;
        final List<ChatFile> attachments;
        final String imageScope;
        volatile boolean toolAttempt;
        volatile boolean searching;
        volatile String pendingImageSize;
        final ModelApi.Cancellation cancellation = new ModelApi.Cancellation();
        final StringBuilder content = new StringBuilder();
        final Choreographer frames = Choreographer.getInstance();
        final Choreographer.FrameCallback paint = time -> renderLatest();
        final Runnable refresh = () -> frames.postFrameCallback(paint);
        boolean queued;
        boolean sealed;
        boolean presented;
        long nextRefresh;
        volatile ChatContext.Checkpoint checkpoint;
        volatile ChatCard card;
        volatile GeneratedImage image;
        volatile List<WebSearch.Hit> sources = List.of();
        volatile ChatReferences references;
        volatile String memoryNote;

        StreamReply(String model, int parent, boolean fromComposer, String searchQuery, List<ChatFile> attachments,
                    String imageScope) {
            this.model = model;
            this.parent = parent;
            this.fromComposer = fromComposer;
            this.searchQuery = searchQuery;
            this.attachments = List.copyOf(attachments);
            this.imageScope = imageScope;
        }

        synchronized boolean publishImage(GeneratedImage result) {
            if (sealed) return false;
            image = result;
            return true;
        }

        synchronized String snapshot() { return content.toString(); }

        synchronized void append(String delta) {
            if (sealed) return;
            content.append(delta);
            if (queued) return;
            queued = true;
            ui.postAtTime(refresh, Math.max(nextRefresh, SystemClock.uptimeMillis()));
        }

        private void renderLatest() {
            String latest = snapshot();
            long started = SystemClock.uptimeMillis();
            showStream(this, latest, true);
            long elapsed = SystemClock.uptimeMillis() - started;
            synchronized (this) {
                nextRefresh = SystemClock.uptimeMillis() + Math.max(MIN_REFRESH_MS, Math.min(MAX_REFRESH_MS, elapsed * 2));
                queued = !sealed && content.length() != latest.length();
                if (queued) ui.postAtTime(refresh, nextRefresh);
            }
        }

        synchronized void seal() {
            sealed = true;
            ui.removeCallbacks(refresh);
            frames.removeFrameCallback(paint);
        }
    }

    private void startReply(String message, String model, int parent, boolean fromComposer, String status, ReplySource source) {
        startReply(message, model, parent, fromComposer, status, null, source);
    }

    private void startReply(String message, String model, int parent, boolean fromComposer, String status, String searchQuery,
                            ReplySource source) {
        startReply(message, model, parent, fromComposer, status, searchQuery, List.of(), "", source);
    }

    private void startReply(String message, String model, int parent, boolean fromComposer, String status, String searchQuery,
                            List<ChatFile> attachments, String imageScope, ReplySource source) {
        StreamReply stream = new StreamReply(model, parent, fromComposer, searchQuery, attachments, imageScope);
        streamReply = stream;
        sending = true;
        pendingPrompt = message;
        clearReplyNotice();
        replyStatus = status;
        if (fromComposer) {
            draft = "";
            draftFiles = List.of();
        }
        if (input != null) {
            hideKeyboard();
            if (fromComposer) input.setText("");
            input.setEnabled(false);
            renderDraftFiles();
            updateSend(sendButton);
        }
        renderMessages();
        worker.execute(() -> {
            String failure = null;
            try { source.read(stream); }
            catch (Exception error) { failure = networkMessage(error); }
            String problem = failure;
            ui.post(() -> finishReply(stream, problem == null ? ChatHistory.ReplyState.COMPLETE
                    : ChatHistory.ReplyState.INTERRUPTED, problem));
        });
    }

    private void stopReply() {
        StreamReply stream = streamReply;
        if (stream == null) return;
        cancelStream(stream);
        finishReply(stream, ChatHistory.ReplyState.STOPPED, "已停止生成");
    }

    private void cancelStream(StreamReply stream) {
        stream.seal();
        Runnable disconnect = stream.cancellation.cancel();
        new Thread(disconnect, "serein-disconnect").start();
    }

    private void replyRecovery(LinearLayout parent, ChatStore.Chat chat, int id, ChatHistory.Turn turn, String problem) {
        String label = switch (turn.replyState()) {
            case COMPLETE -> throw new IllegalArgumentException("A complete reply does not need recovery");
            case STOPPED -> "已停止 · 已保留部分回复";
            case INTERRUPTED -> "回复未完成 · 已保留部分回复";
        };
        if (problem != null) label += "\n" + problem;
        TextView marker = text(label, 13, muted, false);
        marker.setPadding(dp(8), dp(4), dp(8), 0);
        protectOnWallpaper(marker);
        parent.addView(marker);
        addRetryButton(parent, turn.searchQuery() == null ? "重试回复" : "重试搜索", () -> resendTurn(chat, id));
    }

    private void addRetryButton(LinearLayout parent, String label, Runnable send) {
        View retry = action(label, R.drawable.ic_refresh, 0, ink, tint);
        retry.setOnClickListener(v -> send.run());
        parent.addView(retry, new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private void resendTurn(ChatStore.Chat chat, int id) {
        if (store.active != chat || !turnAnchors.containsKey(id)) return;
        resend(ReplyRetry.forTurn(chat, id));
    }

    private void resend(ReplyRetry attempt) {
        if (sending) { notice("请先停止当前生成"); return; }
        if (importingFile || !attempt.matches(store.active)) return;
        boolean consumes = attempt.consumesDraft(draft.strip(), draftFiles);
        if (attempt.searchQuery() == null) submit(attempt.prompt(), attempt.parent(), consumes, () -> {}, null, attempt.attachments());
        else showWebSearch(attempt.prompt(), attempt.parent(), consumes, attempt.searchQuery(), attempt.attachments());
    }

    private void clearReplyNotice() {
        ReplyRetry previous = retryReply;
        retryReply = null;
        replyStatus = null;
        if (previous != null) {
            var ids = new java.util.HashSet<String>();
            for (ChatFile file : previous.attachments()) ids.add(file.id());
            removeUnusedFiles(ids);
        }
    }

    private void finishReply(StreamReply stream, ChatHistory.ReplyState outcome, String problem) {
        if (streamReply != stream || isDestroyed() || isFinishing()) return;
        stream.seal();
        String message = pendingPrompt;
        String reply = stream.snapshot();
        ChatCard card = outcome == ChatHistory.ReplyState.COMPLETE ? stream.card : null;
        GeneratedImage image = stream.image;
        List<ChatFile> references = stream.references == null ? List.of() : stream.references.citations();
        if (image != null && reply.isBlank()) reply = "已生成图片";
        if (!stream.sources.isEmpty()) {
            reply = reply.isBlank() ? WebSearch.references(stream.sources, true)
                    : reply + ChatArtifacts.closingFence(reply) + "\n\n" + WebSearch.references(stream.sources, false);
        }
        if (liveMedia != null && image == null) liveMedia.removeAllViews();
        if (liveMedia != null && image != null && (liveMedia.getChildCount() == 0 || liveMedia.getChildAt(0) instanceof ImagePlaceholder)) {
            renderToolMedia(stream, true);
        }
        stream.searching = false;
        showStream(stream, reply, outcome == ChatHistory.ReplyState.COMPLETE);
        streamReply = null;
        sending = false;
        pendingPrompt = null;
        replyStatus = problem;
        boolean saved = false;
        if (reply.isBlank() && card == null) {
            if (replyStatus == null) replyStatus = "模型没有返回可显示的文本。";
            if (stream.fromComposer) restoreDraft(stream, message);
            if (!stream.toolAttempt) {
                retryReply = new ReplyRetry(store.active, stream.parent, message, stream.fromComposer, stream.searchQuery, stream.attachments);
            }
        } else {
            try {
                store.append(stream.parent, message, reply, stream.model, stream.checkpoint, card, outcome, stream.searchQuery,
                        image, stream.attachments, references, stream.imageScope);
                saved = true;
                clearReplyNotice();
            }
            catch (IllegalStateException error) {
                if (stream.fromComposer) restoreDraft(stream, message);
                replyStatus = "收到回复，但无法保存本机聊天记录。";
            }
        }
        if (image != null && !saved) localWork.execute(() -> images.remove(image.id()));
        if (input != null) {
            input.setEnabled(true);
            input.setText(draft);
            input.setSelection(input.length());
            renderDraftFiles();
            updateSend(sendButton);
            ReplyText current = liveReply;
            if (current != null && saved) {
                current.setTextIsSelectable(true);
                current.setMovementMethod(LinkMovementMethod.getInstance());
                liveStatus.setVisibility(View.GONE);
                int id = store.active.history.leaf();
                turnAnchors.put(id, liveAnchors);
                directoryButton.setVisibility(View.VISIBLE);
                if (card != null) addCard(liveActions, store.active, id);
                addFileReferences(liveActions, references);
                if (outcome != ChatHistory.ReplyState.COMPLETE) {
                    ChatStore.Chat chat = store.active;
                    replyRecovery(liveActions, chat, id, chat.history.turn(id),
                            outcome == ChatHistory.ReplyState.INTERRUPTED ? problem : null);
                }
                messageActions(liveActions, id, liveHasArtifacts);
            } else renderMessages(chatScroll.following);
        }
        if (saved && outcome == ChatHistory.ReplyState.COMPLETE && stream.memoryNote != null
                && prefs.getBoolean("chat_memory", false)) {
            try {
                if (memory.remember(stream.memoryNote, message) == ChatMemory.Outcome.SAVED) {
                    if (page.equals(MEMORIES)) render();
                    notice("已保存一条记忆");
                }
            }
            catch (IllegalStateException error) { notice("无法保存本机记忆"); }
        }
    }

    private void restoreDraft(StreamReply stream, String message) {
        draft = message;
        draftFiles = stream.attachments;
    }

    private void newChat() {
        if (sending) { notice("正在等待回复"); return; }
        confirmLeavingDraft(() -> {
            hideKeyboard();
            try { store.open(null); }
            catch (IllegalStateException error) { notice("无法保存会话状态"); return; }
            discardDraft();
            go(CHAT);
        });
    }

    private void confirmLeavingDraft(Runnable action) {
        if (importingFile) { notice("正在导入文件"); return; }
        if (draft.isBlank() && draftFiles.isEmpty()) action.run();
        else showDialog(dialogBuilder().setTitle("舍弃当前草稿？")
                .setMessage(draftFiles.isEmpty() ? "还有尚未发送的输入。" : "尚未发送的附件会随草稿一起舍弃。")
                .setNegativeButton("取消", null)
                .setPositiveButton("舍弃并继续", (dialog, index) -> action.run()).create(), true);
    }

    private void discardDraft() {
        var candidates = new java.util.HashSet<String>();
        for (ChatFile file : draftFiles) candidates.add(file.id());
        draft = "";
        draftFiles = List.of();
        clearReplyNotice();
        removeUnusedFiles(candidates);
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
        drawerRail = rail;
        rail.setBackgroundColor(chatSurface(panel, muted));
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
        brand.setSingleLine(true);
        brand.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1));
        header.addView(iconButton("搜索会话", R.drawable.ic_search, ink, 0, () -> go(SEARCH)));
        View close = iconButton("关闭侧栏", R.drawable.ic_close, ink, 0, this::closeDrawer);
        header.addView(close);
        rail.addView(header);
        space(rail, 16);
        View start = action("新建聊天", R.drawable.ic_plus, 0, Appearance.readable(accent, ink, tint), tint);
        start.setOnClickListener(v -> newChat());
        rail.addView(start, new LinearLayout.LayoutParams(MATCH, WRAP));
        space(rail, 14);
        ScrollView historyScroll = scroll();
        LinearLayout historyPane = column();
        LinearLayout history = column();
        MaterialButton archive = action(drawerArchived ? "最近会话" : "已归档",
                R.drawable.ic_archive, 0, ink, 0);
        archive.setOnClickListener(v -> {
            drawerArchived = !drawerArchived;
            archive.setText(drawerArchived ? "最近会话" : "已归档");
            renderHistory(history);
            historyScroll.scrollTo(0, 0);
        });
        historyPane.addView(archive, new LinearLayout.LayoutParams(MATCH, WRAP));
        renderHistory(history);
        historyPane.addView(history);
        historyScroll.addView(historyPane);
        rail.addView(historyScroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        rule(rail);
        String name = profile.displayName();
        addRow(rail, "资料库", "", R.drawable.ic_file, () -> openFileSpace(false));
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

    private void renderHistory(LinearLayout history) {
        history.removeAllViews();
        if (drawerArchived) {
            sectionTitle(history, "已归档");
            boolean hasArchived = false;
            for (ChatStore.Chat chat : store.recent) {
                if (!chat.archived) continue;
                hasArchived = true;
                addHistoryRow(history, chat);
            }
            if (!hasArchived) addHistoryEmpty(history, "暂无归档会话");
            return;
        }
        boolean hasPinned = store.recent.stream().anyMatch(chat -> chat.pinned && !chat.archived);
        boolean hasRecent = store.recent.stream().anyMatch(chat -> !chat.pinned && !chat.archived);
        if (!hasPinned && !hasRecent) {
            sectionTitle(history, "最近会话");
            addHistoryEmpty(history, "暂无会话");
        }
        if (hasPinned) {
            sectionTitle(history, "置顶");
            for (ChatStore.Chat chat : store.recent) if (chat.pinned && !chat.archived) addHistoryRow(history, chat);
        }
        if (hasRecent) {
            sectionTitle(history, "最近会话");
            for (ChatStore.Chat chat : store.recent) if (!chat.pinned && !chat.archived) addHistoryRow(history, chat);
        }
    }

    private void addHistoryEmpty(LinearLayout history, String label) {
        TextView empty = text(label, 14, muted, false);
        empty.setPadding(dp(8), dp(10), dp(8), dp(10));
        history.addView(empty);
    }

    private void addHistoryRow(LinearLayout parent, ChatStore.Chat chat) {
        boolean current = chat == store.active;
        LinearLayout entry = row();
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setBackground(shape(current ? tint : Color.TRANSPARENT, 8, 0));
        LinearLayout item = column();
        item.setPadding(dp(9), dp(9), dp(9), dp(9));
        item.setMinimumHeight(dp(64));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(ripple(0, 8));
        item.setSelected(current);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(chat.title, 15, ink, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        String date = DateFormat.getDateInstance(DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(chat.updatedAt);
        TextView time = text(date, 12, muted, false);
        time.setPadding(dp(7), 0, 0, 0);
        top.addView(time);
        item.addView(top);
        LinearLayout bottom = row();
        String last = chat.history.turn(chat.history.leaf()).promptLabel().replace('\n', ' ');
        TextView preview = text(last, 13, muted, false);
        preview.setMaxLines(1);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(preview, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (current) bottom.addView(icon(R.drawable.ic_check, 18, accent));
        item.addView(bottom);
        item.setFocusable(true);
        item.setOnClickListener(v -> openHistoryChat(chat, -1, false));
        entry.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1));
        MaterialButton more = iconButton("会话选项", R.drawable.ic_more, muted, 0, () -> historyOptions(parent, chat));
        more.setContentDescription("会话选项 · " + chat.title);
        entry.addView(more);
        parent.addView(entry, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void openHistoryChat(ChatStore.Chat chat, int id, boolean answer) {
        if (store.active == chat) {
            if (!page.equals(CHAT)) {
                hideKeyboard();
                go(CHAT);
            } else closeDrawer();
            if (id >= 0) jumpTo(chat, id, answer);
            return;
        }
        if (sending) { notice("正在等待回复"); return; }
        confirmLeavingDraft(() -> {
            try { store.open(chat); }
            catch (IllegalStateException error) { notice("无法保存会话状态"); return; }
            hideKeyboard();
            discardDraft();
            go(CHAT);
            if (id >= 0) jumpTo(chat, id, answer);
        });
    }

    private void historyOptions(LinearLayout history, ChatStore.Chat chat) {
        List<MenuItem> items = new ArrayList<>();
        if (!chat.archived) items.add(new MenuItem(chat.pinned ? "取消置顶" : "置顶会话", () -> {
            try { store.pin(chat, !chat.pinned); renderHistory(history); }
            catch (IllegalStateException error) { notice("无法更新本机会话"); }
        }));
        items.add(new MenuItem("重命名", () -> renameChat(history, chat)));
        items.add(new MenuItem(chat.archived ? "恢复到最近会话" : "归档会话", () -> archiveChat(history, chat)));
        items.add(new MenuItem("导出 Markdown", () -> confirmConversationExport(chat)));
        items.add(new MenuItem("删除会话", () -> confirmDeleteChat(history, chat)));
        showMenu(preview(chat.title, 45), items);
    }

    private void archiveChat(LinearLayout history, ChatStore.Chat chat) {
        boolean archive = !chat.archived;
        boolean closeActive = archive && store.active == chat;
        if (closeActive && sending) { notice("请先停止当前生成"); return; }
        Runnable save = () -> {
            try { store.archive(chat, archive); }
            catch (IllegalStateException error) { notice("无法更新本机会话"); return; }
            if (closeActive) {
                discardDraft();
                input.setText("");
                renderDraftFiles();
                renderMessages();
            }
            renderHistory(history);
            notice(archive ? "会话已归档" : "会话已恢复");
        };
        if (closeActive) confirmLeavingDraft(save);
        else save.run();
    }

    private void renameChat(LinearLayout history, ChatStore.Chat chat) {
        LinearLayout form = dialogBody();
        EditText title = field(form, "会话标题", chat.title, false);
        title.setSelectAllOnFocus(true);
        title.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        title.setSaveEnabled(false);
        saveForm("重命名会话", form, true, () -> {
            store.rename(chat, title.getText().toString());
            renderHistory(history);
        }, null);
    }

    private void confirmDeleteChat(LinearLayout history, ChatStore.Chat chat) {
        if ((sending || importingFile) && store.active == chat) { notice("请等待当前操作完成"); return; }
        AlertDialog dialog = dialogBuilder().setTitle("删除会话？")
                .setMessage("「" + preview(chat.title, 45) + "」及其全部消息版本将从本机删除，无法撤销。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, index) -> {
                    boolean current = store.active == chat;
                    if (current && (sending || importingFile)) { notice("请等待当前操作完成"); return; }
                    var candidates = new java.util.HashSet<String>();
                    var fileIds = new java.util.HashSet<String>();
                    chat.history.collectImageIds(candidates);
                    chat.history.collectFileIds(fileIds);
                    try { store.delete(chat); }
                    catch (IllegalStateException error) { notice("无法删除本机会话"); return; }
                    candidates.removeAll(imageReferences());
                    if (!candidates.isEmpty()) localWork.execute(() -> candidates.forEach(images::remove));
                    if (current) {
                        discardDraft();
                        input.setText("");
                        renderDraftFiles();
                        renderMessages();
                    }
                    removeUnusedFiles(fileIds);
                    renderHistory(history);
                }).create();
        showDialog(dialog, true);
    }

    private void closeDrawer() {
        if (drawer == null) return;
        root.removeView(drawer);
        drawer = null;
        drawerRail = null;
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
        else if (page.equals(SETTINGS) || page.equals(ACCOUNT) || page.equals(SEARCH) || page.equals(FILE_SPACE)) {
            hideKeyboard();
            boolean reopenDrawer = !page.equals(FILE_SPACE) || !fileSpaceFromComposer;
            go(CHAT);
            if (reopenDrawer) openDrawer();
        } else if (page.equals("provider")) go("providers");
        else if (page.equals(MEMORIES)) go("plugins");
        else if (page.equals(APP_UPDATE)) go("about");
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
            case SEARCH -> "搜索会话";
            case FILE_SPACE -> "资料库";
            case MEMORIES -> "记忆库";
            case APP_UPDATE -> "应用更新";
            case "provider" -> providers.get(providerPage).label;
            default -> findSection(page).title();
        };
        TextView title = text(heading, 21, ink, true);
        title.setAccessibilityHeading(true);
        title.setPadding(dp(5), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        screen.addView(toolbar);
        if (page.equals(SEARCH)) {
            renderHistorySearch();
            return;
        }
        ScrollView scroll = scroll();
        if (page.equals("appearance")) {
            appearanceScroll = scroll;
            addAppearancePreview();
        }
        LinearLayout body = column();
        body.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(body);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));

        switch (page) {
            case SETTINGS -> settings(body);
            case ACCOUNT -> account(body);
            case FILE_SPACE -> fileSpacePage(body);
            case MEMORIES -> memoryPage(body);
            case APP_UPDATE -> {
                scroll.setFillViewport(true);
                updateBody = body;
                onAppUpdate(appUpdate.current());
            }
            case "appearance" -> appearance(body);
            case "providers" -> providerList(body);
            case "provider" -> providerDetails(body, providers.get(providerPage));
            case "plugins" -> chatExtensions(body);
            case "about" -> {
                body.addView(logo(68));
                space(body, 10);
                body.addView(text("Serein", 24, ink, true));
                body.addView(text("Alpha " + appVersionName().replaceFirst("-alpha$", "") + " · Android 13+", 14, muted, false));
                space(body, 12);
                addRow(body, "应用更新", "GitHub Releases", R.drawable.ic_refresh, this::openAppUpdate);
                addRow(body, "热更新模型目录", "立即应用到模型选择和识别", R.drawable.ic_cpu, this::refreshModelCatalog);
                space(body, 20);
                body.addView(text("Serein is a Chat App. Not an Agent. Not a Workspace. Just Chat.", 16, ink, false));
            }
            case "policy" -> dataPolicy(body);
            case "licenses" -> {
                license(body, "Lucide 图标 · ISC 许可", "lucide");
                license(body, "Lobe Icons · MIT 许可", "lobe-icons");
                license(body, "Tabler Icons · MIT 许可", "tabler-icons");
                license(body, "Heroicons · MIT 许可", "heroicons");
                license(body, "SVG Repo 滑动图标 · CC0", "svgrepo-swipe");
                license(body, "LiteLLM 模型目录 · MIT 许可", "litellm");
                license(body, "commonmark-java · BSD 2-Clause 许可", "commonmark");
                license(body, "AstraAgent · MIT 许可", "astraagent");
                license(body, "AutoModelRouter · CC0", "auto-model-router");
                license(body, "Material Components 与 AndroidX · Apache 2.0 许可", "material-components");
            }
        }
    }

    private void openAppUpdate() {
        go(APP_UPDATE);
        if (appUpdate.current().phase() == AppUpdate.Phase.IDLE) appUpdate.check();
    }

    private void fileSpacePage(LinearLayout body) {
        var entries = files.library();
        var access = settingSwitch(body, "允许模型检索资料库", prefs.getBoolean("chat_file_space", false));
        access.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("chat_file_space", checked).apply());
        body.addView(text("命中的资料摘录会发送给当前聊天服务商。", 13, muted, false));
        space(body, 12);
        var add = action(importingFile ? "正在导入…" : "导入文件", R.drawable.ic_plus, 0, onAccent, accent);
        add.setEnabled(!importingFile);
        add.setOnClickListener(v -> pickFile(true));
        body.addView(add, new LinearLayout.LayoutParams(WRAP, WRAP));
        space(body, 18);
        if (entries.isEmpty()) {
            body.addView(text("暂无资料", 16, ink, true));
            body.addView(text("文本、CSV、JSON、DOCX · 单文件最多 5 MiB", 13, muted, false));
            return;
        }
        sectionTitle(body, "本机资料 · " + entries.size());
        for (ChatFile file : entries) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout label = column();
            addRow(label, file.name(), fileSize(file), R.drawable.ic_file, () -> previewFile(file, true));
            item.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1));
            item.addView(iconButton("资料选项 · " + file.name(), R.drawable.ic_more, muted, 0,
                    () -> fileOptions(file)));
            body.addView(item, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private void fileOptions(ChatFile file) {
        List<MenuItem> items = new ArrayList<>();
        if (!sending && !importingFile) {
            items.add(new MenuItem("附加到聊天", () -> importFile(false, () -> files.copyToChat(file), true)));
        }
        items.add(new MenuItem("从资料库移除", () -> showDialog(dialogBuilder().setTitle("移除资料？")
                .setMessage(file.name() + "\n\n已发送的聊天附件和回复会保留。")
                .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, index) -> {
                    localWork.execute(() -> {
                        boolean removed;
                        try { files.removeLibrary(file); removed = true; }
                        catch (RuntimeException error) { removed = false; }
                        boolean success = removed;
                        ui.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (page.equals(FILE_SPACE)) render();
                            if (!success) notice("无法移除本机资料");
                        });
                    });
                }).create(), true)));
        showMenu(file.name(), items);
    }

    private void memoryPage(LinearLayout body) {
        var access = settingSwitch(body, "主动记忆", prefs.getBoolean("chat_memory", false));
        access.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("chat_memory", checked).apply());
        body.addView(text("命中的记忆会发送给当前聊天服务商。", 13, muted, false));
        space(body, 18);
        List<ChatMemory.Entry> entries = memory.entries();
        if (entries.isEmpty()) {
            body.addView(text("暂无记忆", 16, ink, true));
            return;
        }
        sectionTitle(body, "本机记忆 · " + entries.size());
        DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (ChatMemory.Entry entry : entries) {
            TextView note = text(entry.text(), 15, ink, false);
            note.setTextIsSelectable(true);
            body.addView(note);
            body.addView(text(date.format(new java.util.Date(entry.savedAt())), 12, muted, false));
            rule(body);
        }
        var clear = action("清空记忆", R.drawable.ic_close, 0, ink, tint);
        clear.setOnClickListener(v -> showDialog(dialogBuilder().setTitle("清空本机记忆？")
                .setMessage("这会删除模型保存的全部记忆。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (dialog, index) -> {
                    if (sending) { notice("请先停止当前生成"); return; }
                    try { memory.clear(); render(); }
                    catch (RuntimeException error) { notice("无法清空本机记忆"); }
                }).create(), true));
        body.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private void onAppUpdate(AppUpdate.State state) {
        if (updateBody == null || isFinishing() || isDestroyed()) return;
        AppUpdate.Phase phase = state.phase();
        if (phase == AppUpdate.Phase.DOWNLOADING && visibleUpdatePhase == phase) {
            updateRing.setProgressCompat(state.percent(), motionEnabled());
            updateBytes.setText(updateDownloadLabel(state));
            return;
        }
        boolean animate = visibleUpdatePhase != null && motionEnabled();
        visibleUpdatePhase = phase;
        updateBody.removeAllViews();
        updateBody.setGravity(Gravity.CENTER);
        updateRing = null;
        updateBytes = null;
        var content = column();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        updateBody.addView(content, new LinearLayout.LayoutParams(MATCH, WRAP));

        var badge = new FrameLayout(this);
        content.addView(badge, new LinearLayout.LayoutParams(dp(104), dp(104)));
        var mark = logo(54);
        badge.addView(mark, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        if (phase == AppUpdate.Phase.DOWNLOADING || state.busy() && motionEnabled()) {
            updateRing = new CircularProgressIndicator(controls);
            updateRing.setIndicatorSize(dp(88));
            updateRing.setTrackThickness(dp(5));
            updateRing.setTrackCornerRadius(dp(2));
            updateRing.setIndicatorColor(controlAccent());
            updateRing.setTrackColor(line);
            updateRing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            updateRing.setIndeterminate(phase != AppUpdate.Phase.DOWNLOADING);
            if (phase == AppUpdate.Phase.DOWNLOADING) updateRing.setProgressCompat(state.percent(), false);
            badge.addView(updateRing, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        }

        String heading = switch (phase) {
            case IDLE, CHECKING -> "正在检查更新…";
            case CURRENT -> "已是最新版本";
            case AVAILABLE -> "发现新版本";
            case DOWNLOADING -> "正在下载更新…";
            case VERIFYING -> "正在校验更新包…";
            case READY -> "更新已就绪";
            case INSTALLER -> "等待 Android 安装确认…";
            case ERROR -> "更新未完成";
        };
        space(content, 16);
        TextView headline = centeredUpdateText(content, heading, 23, ink, true);
        headline.setAccessibilityHeading(true);
        headline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        space(content, 7);
        String version = "Alpha " + appVersionName().replaceFirst("-alpha$", "");
        if (state.version() != null) version += " → " + state.version().replaceFirst("-alpha$", "");
        centeredUpdateText(content, version, 14, muted, false);
        if (phase == AppUpdate.Phase.DOWNLOADING) {
            space(content, 7);
            updateBytes = centeredUpdateText(content, updateDownloadLabel(state), 14, ink, false);
        } else if (phase == AppUpdate.Phase.AVAILABLE) {
            space(content, 7);
            centeredUpdateText(content, android.text.format.Formatter.formatShortFileSize(this, state.release().bytes()),
                    14, muted, false);
        }
        if (state.error() != null) {
            space(content, 12);
            centeredUpdateText(content, state.error(), 14, ink, false);
        }
        switch (phase) {
            case CURRENT -> updateButton(content, "重新检查", R.drawable.ic_refresh, appUpdate::check);
            case AVAILABLE -> updateButton(content, "开始更新", R.drawable.ic_refresh, appUpdate::start);
            case READY -> updateButton(content, "安装更新", R.drawable.ic_check, this::installUpdate);
            case ERROR -> updateButton(content, "重试", R.drawable.ic_refresh,
                    state.release() == null ? appUpdate::check : appUpdate::start);
        }
        Motion.enter(content, 0, dp(6), .988f, animate);
    }

    private TextView centeredUpdateText(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView label = text(value, size, color, bold);
        label.setGravity(Gravity.CENTER);
        parent.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
        return label;
    }

    private String updateDownloadLabel(AppUpdate.State state) {
        return state.percent() + "% · " + android.text.format.Formatter.formatShortFileSize(this, state.received())
                + " / " + android.text.format.Formatter.formatShortFileSize(this, state.release().bytes());
    }

    private void updateButton(LinearLayout parent, String label, int icon, Runnable tap) {
        space(parent, 20);
        var button = action(label, icon, 0, onAccent, accent);
        button.setOnClickListener(v -> tap.run());
        parent.addView(button, new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private void installUpdate() {
        if (appUpdate.current().phase() != AppUpdate.Phase.READY || sending || importingFile) {
            notice("请等待当前操作完成");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            showDialog(dialogBuilder().setTitle("允许 Serein 发起安装？")
                    .setMessage("在 Android 设置中允许后，返回此页点「安装更新」。")
                    .setNegativeButton("取消", null).setPositiveButton("打开设置", (d, i) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.fromParts("package", getPackageName(), null)));
                        } catch (android.content.ActivityNotFoundException | SecurityException error) { notice("无法打开安装设置"); }
                    }).create(), false);
            return;
        }
        appUpdate.verifyForInstall(this::openUpdateInstaller);
    }

    private void openUpdateInstaller() {
        if (isFinishing() || isDestroyed() || !hasWindowFocus() || !page.equals(APP_UPDATE) || sending || importingFile) return;
        try {
            var uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".updates", SereinUpdates.apk(this));
            startActivityForResult(new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), INSTALL_UPDATE);
            appUpdate.handOff();
        } catch (RuntimeException error) { appUpdate.installationFailed("无法打开 Android 安装程序。"); }
    }

    private void refreshModelCatalog() {
        if (!beginUpdate("正在校验模型目录…")) return;
        worker.execute(() -> {
            try {
                ModelCatalog catalog = SereinUpdates.refreshCatalog(this);
                finishUpdate(() -> {
                    if (catalog == null) { notice("模型目录已是最新"); return; }
                    modelCatalog = catalog;
                    if (!sending) render();
                    notice("模型目录已更新");
                });
            } catch (Exception error) { finishUpdate(() -> notice("无法更新模型目录，请稍后重试")); }
        });
    }

    private boolean beginUpdate(String status) {
        if (updateRunning) { notice("更新正在处理"); return false; }
        updateRunning = true;
        notice(status);
        return true;
    }

    private void finishUpdate(Runnable completion) {
        ui.post(() -> {
            updateRunning = false;
            if (!isDestroyed() && !isFinishing()) completion.run();
        });
    }

    private void renderHistorySearch() {
        View owner = screen;
        List<ChatSearch.Source> snapshot = ChatSearch.snapshot(store.recent);
        LinearLayout filters = column();
        filters.setPadding(dp(20), dp(8), dp(20), 0);
        EditText query = field(filters, "会话标题或内容", "", false);
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ChatSearch.QUERY_LIMIT)});
        query.setSaveEnabled(false);
        query.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        MaterialSwitch archives = settingSwitch(filters, "包含已归档会话", drawerArchived);
        screen.addView(filters);

        TextView status = text("", 13, muted, false);
        status.setPadding(dp(20), dp(8), dp(20), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        screen.addView(status);
        ScrollView scroll = scroll();
        LinearLayout matches = column();
        matches.setPadding(dp(20), 0, dp(20), dp(12));
        scroll.addView(matches);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));

        Runnable search = () -> {
            cancelHistorySearch();
            String value = query.getText().toString();
            boolean includeArchives = archives.isChecked();
            matches.removeAllViews();
            boolean empty = value.isBlank();
            status.setVisibility(empty ? View.GONE : View.VISIBLE);
            if (empty) return;
            status.setText("搜索中…");
            int generation = historyQueryGeneration;
            queuedHistorySearch = () -> runningHistorySearch = localWork.submit(() -> {
                ChatSearch.Results found = ChatSearch.find(snapshot, value, includeArchives);
                ui.post(() -> {
                    if (owner != screen || isDestroyed() || generation != historyQueryGeneration) return;
                    int count = found.hits().size();
                    status.setText(count == 0 ? "没有匹配的会话" : found.limited()
                            ? "前 " + count + " 个匹配会话" : count + " 个匹配会话");
                    for (ChatSearch.Hit hit : found.hits()) {
                        String label = hit.excerpt();
                        if (hit.source().archived()) label = label.isEmpty() ? "已归档" : "已归档 · " + label;
                        addRow(matches, hit.source().title(), label,
                                hit.source().archived() ? R.drawable.ic_archive : R.drawable.ic_file,
                                () -> openHistoryChat(hit.source().chat(), hit.id(), hit.answer()));
                        rule(matches);
                    }
                    scroll.scrollTo(0, 0);
                });
            });
            ui.postDelayed(queuedHistorySearch, 150);
        };
        watch(query, ignored -> search.run());
        archives.setOnCheckedChangeListener((button, checked) -> search.run());
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        query.post(() -> {
            if (owner != screen || isDestroyed()) return;
            query.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void cancelHistorySearch() {
        historyQueryGeneration++;
        if (queuedHistorySearch != null) ui.removeCallbacks(queuedHistorySearch);
        if (runningHistorySearch != null) runningHistorySearch.cancel(true);
        queuedHistorySearch = null;
        runningHistorySearch = null;
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
        if (isFinishing() || isDestroyed()) return;
        hideKeyboard();
        if (activeNotice != null) activeNotice.dismiss();
        AppCompatDialog dialog = new AppCompatDialog(controls);
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = column();
        sheet.setPadding(dp(12), dp(8), dp(12), dp(8));
        sheet.setFocusableInTouchMode(true);
        sheet.setAccessibilityPaneTitle("选择模型");
        sheet.setBackground(UiKit.sheetSurface(controls, panel, line));

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout searchBar = row();
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(10), 0, dp(6), 0);
        searchBar.setBackground(shape(paper, 8, line));
        searchBar.addView(icon(R.drawable.ic_search, 18, muted));
        EditText search = UiKit.inlineField(controls);
        search.setHint("搜索模型");
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setMinimumHeight(dp(48));
        search.setPadding(dp(8), dp(4), 0, dp(4));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            var controller = search.getWindowInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
            return true;
        });
        searchBar.addView(search, new LinearLayout.LayoutParams(0, WRAP, 1));
        LinearLayout.LayoutParams searchPosition = new LinearLayout.LayoutParams(0, WRAP, 1);
        searchPosition.setMarginEnd(dp(4));
        toolbar.addView(searchBar, searchPosition);
        toolbar.addView(iconButton("服务商设置", R.drawable.ic_settings, ink, 0, () -> {
            dialog.dismiss();
            go("providers");
        }));
        toolbar.addView(iconButton("关闭模型选择", R.drawable.ic_close, ink, 0, dialog::dismiss));
        sheet.addView(toolbar, new LinearLayout.LayoutParams(MATCH, WRAP));
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
        dialog.setContentView(sheet);
        sheet.requestFocus();
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_SECURE);
        window.setDimAmount(.35f);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        var bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        boolean shortWindow = bounds.height() < dp(520);
        int contentHeight = Math.min(496, 76 + 64 * options.size());
        int heightLimit = shortWindow ? bounds.height() : Math.round(bounds.height() * .65f);
        int preferredHeight = dp(shortWindow ? 496 : Math.max(280, contentHeight));
        window.setLayout(Math.min(bounds.width(), dp(520)), Math.min(preferredHeight, heightLimit));
        dialog.show();
        trackDialog(dialog);
        Motion.enter(sheet, 0, dp(24), .99f, motionEnabled());
    }

    private static boolean matches(String name, String terms) {
        for (String term : terms.split("\\s+")) if (!name.contains(term)) return false;
        return true;
    }

    private void changeChoice(Dialog dialog, Providers.Provider provider, Providers.Model model) {
        try {
            providers.select(provider, model);
            prefs.edit().putBoolean("auto_route", false).apply();
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
        command(body, "移除服务商", R.drawable.ic_close, () -> showDialog(dialogBuilder().setTitle("移除 " + provider.label + "？")
                .setNegativeButton("取消", null).setPositiveButton("移除", (d, i) -> {
                    try { providers.remove(provider); go("providers"); }
                    catch (IllegalStateException error) { notice("无法更新本机配置"); }
                }).create(), true));
    }

    private void editProvider(Providers.Provider old) {
        LinearLayout form = dialogBody();
        EditText name = field(form, "服务商名称", old == null ? "" : old.label, false);
        EditText base = field(form, "HTTPS 根地址（含 API 路径）", old == null ? "" : old.base, false);
        if (old != null && !old.keys.isEmpty()) base.setEnabled(false);
        saveForm(old == null ? "添加服务商" : "连接设置", form, false, () -> {
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
        showMenu(provider.keys.get(position).label(), List.of(
                new MenuItem("使用这把 Key", () -> putProvider(provider.credentials(provider.keys, position))),
                new MenuItem("编辑", () -> editKey(provider, position)),
                new MenuItem("移除", () -> showDialog(dialogBuilder().setTitle("移除这把 Key？")
                            .setNegativeButton("取消", null).setPositiveButton("移除", (d, i) -> {
                                List<Providers.Key> updated = new ArrayList<>(provider.keys);
                                updated.remove(position);
                                int selected = provider.activeKey > position ? provider.activeKey - 1 : provider.activeKey;
                                putProvider(provider.credentials(updated, selected));
                            }).create(), true))));
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
            showDialog(dialogBuilder().setTitle("模型图标")
                    .setSingleChoiceItems(labels, selected, (dialog, position) -> {
                        iconOverride[0] = ModelIcons.OPTIONS.get(position).id();
                        refresh.run();
                        dialog.dismiss();
                    }).setNegativeButton("取消", null).create(), true);
        });
        android.widget.RadioGroup modes = new android.widget.RadioGroup(this);
        form.addView(modes);
        for (int i = 0; i < REASONING_MODES.length; i++) {
            var button = new com.google.android.material.radiobutton.MaterialRadioButton(controls);
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
        EditText edit = field(form, label, value, false);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
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
        var builder = dialogBuilder().setTitle("正在拉取模型").setNegativeButton("取消", null);
        if (motionEnabled()) {
            LinearLayout body = dialogBody();
            var indicator = new LinearProgressIndicator(controls);
            indicator.setIndeterminate(true);
            indicator.setIndicatorColor(controlAccent());
            body.addView(indicator, new LinearLayout.LayoutParams(MATCH, WRAP));
            builder.setView(body);
        }
        AlertDialog progress = builder.create();
        showDialog(progress, false);
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

    private String appVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { return ""; }
    }

    private String networkMessage(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请重试。";
        if (error instanceof javax.net.ssl.SSLException) return "无法验证 HTTPS 连接。";
        if (error instanceof java.net.UnknownHostException) return "无法找到服务商地址。";
        if (error instanceof IOException && error.getMessage() != null &&
                (error.getMessage().startsWith("服务商") || error.getMessage().startsWith("模型")
                        || error.getMessage().startsWith("DeepSeek 搜索") || error.getMessage().startsWith("生图")
                        || error.getMessage().startsWith("附件"))) return error.getMessage();
        return "请求失败，请检查连接与模型 ID。";
    }

    private void notice(String text) {
        if (root == null || !root.isAttachedToWindow() || isFinishing() || isDestroyed()) return;
        openDialogs.removeIf(dialog -> !dialog.isShowing());
        View host = root;
        View anchor = drawer == null ? composerAnchor : null;
        if (!openDialogs.isEmpty()) {
            Dialog top = openDialogs.get(openDialogs.size() - 1);
            View content = top.findViewById(android.R.id.content);
            if (content != null && content.isAttachedToWindow()) {
                host = content;
                anchor = null;
                if (top instanceof AlertDialog alert) {
                    anchor = alert.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (anchor == null || anchor.getVisibility() != View.VISIBLE) {
                        anchor = alert.getButton(AlertDialog.BUTTON_NEGATIVE);
                    }
                }
                if (anchor != null && anchor.getVisibility() != View.VISIBLE) anchor = null;
            }
        }
        if (activeNotice != null) activeNotice.dismiss();
        activeNotice = UiOverlays.notice(controls, host, anchor, text, ink, paper, motionEnabled());
        activeNotice.show();
    }

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
        return body;
    }

    private EditText field(LinearLayout body, String label, String value, boolean secret) {
        TextInputEditText edit = UiKit.field(controls, body, label, value, ink, muted, line, controlAccent());
        if (secret) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            edit.setSaveEnabled(false);
        }
        return edit;
    }

    private EditText multilineField(LinearLayout body, String label, String value, int limit) {
        EditText edit = field(body, label, value, false);
        edit.setSingleLine(false);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setMinLines(3);
        edit.setMaxLines(8);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        edit.setSaveEnabled(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return edit;
    }

    private void saveForm(String title, LinearLayout form, boolean secure, Runnable save, Runnable remove) {
        saveForm(title, form, secure, "保存", save, remove);
    }

    private void saveForm(String title, LinearLayout form, boolean secure, String positiveLabel, Runnable save, Runnable remove) {
        ScrollView scroll = scroll();
        scroll.addView(form);
        TextView errorText = text("", 14, ink, true);
        form.addView(errorText);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorText.setVisibility(View.GONE);
        MaterialAlertDialogBuilder builder = dialogBuilder().setTitle(title).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton(positiveLabel, null);
        if (remove != null) builder.setNeutralButton("移除", (dialog, index) -> remove.run());
        AlertDialog dialog = builder.create();
        showDialog(dialog, secure);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { save.run(); dialog.dismiss(); }
            catch (IllegalArgumentException error) { errorText.setText(error.getMessage()); errorText.setVisibility(View.VISIBLE); }
            catch (IllegalStateException error) { errorText.setText("无法保存本机配置。"); errorText.setVisibility(View.VISIBLE); }
        });
    }

    private MaterialAlertDialogBuilder dialogBuilder() {
        if (controls == null) return new MaterialAlertDialogBuilder(this);
        return UiOverlays.dialog(controls, panel, line);
    }

    private void showDialog(AlertDialog dialog, boolean secure) {
        if (activeNotice != null) activeNotice.dismiss();
        UiOverlays.show(dialog, secure, motionEnabled(), controls == null ? color("#235543") : controlAccent());
        trackDialog(dialog);
    }

    private void trackDialog(Dialog dialog) {
        openDialogs.add(dialog);
        dialog.setOnDismissListener(dismissed -> openDialogs.remove(dialog));
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

    private void dataPolicy(LinearLayout body) {
        addRow(body, "清除发送确认", "下次在线发送时重新确认接收方", R.drawable.ic_shield, () ->
                showDialog(dialogBuilder().setTitle("清除发送确认？")
                        .setNegativeButton("取消", null).setPositiveButton("清除", (dialog, index) -> {
                            var editor = prefs.edit();
                            for (String key : prefs.getAll().keySet()) {
                                if (ChatDelivery.isConfirmation(key)) editor.remove(key);
                            }
                            editor.apply();
                            notice("发送确认已清除");
                        }).create(), true));
        space(body, 18);
        body.addView(text("会话、附件、资料库、记忆、生成图片、账户资料及服务商配置在本机加密保存。"
                + "在线发送前会按接收方和发送范围请求确认。清除确认记录无法撤回已发送的内容。\n\n"
                + "聊天服务商可接收对话上下文、随附图片和相关附件摘录。开启资料库、记忆或账户资料分享后，"
                + "检索命中的内容或账户资料也可能发送。上下文压缩可能另行发送较早的消息。\n\n"
                + "DeepSeek Search 接收检索词，结果摘要回传聊天模型。通过 Bing 打开时，浏览器会向 Bing 发送检索词。"
                + "图像服务商接收生图描述。导出的文件离开应用内加密存储。交互卡片的选择留在本机。", 16, ink, false));
    }

    private void account(LinearLayout body) {
        EditText name = field(body, "显示名称", profile.name(), false);
        name.setSaveEnabled(false);
        name.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
        space(body, 14);
        EditText bio = multilineField(body, "个人简介", profile.bio(), 1000);
        MaterialSwitch share = settingSwitch(body, "随聊天发送账户资料", profile.share());
        body.addView(text("开启后，名称和简介作为系统提示词发送给在线服务商。", 13, muted, false));
        space(body, 14);
        View save = action("保存", R.drawable.ic_check, 0, onAccent, accent);
        save.setOnClickListener(v -> {
            AccountProfile next = new AccountProfile(name.getText().toString().strip(), bio.getText().toString().strip(), share.isChecked());
            try {
                vault.write("profile", next.encode(), "profile_name");
                profile = next;
                navigateBack();
            } catch (IllegalStateException error) { notice("无法保存账户资料"); }
        });
        body.addView(save, new LinearLayout.LayoutParams(WRAP, WRAP));
    }

    private MaterialSwitch settingSwitch(LinearLayout body, String label, boolean checked) {
        MaterialSwitch toggle = UiKit.toggle(controls, label, ink, controlAccent(), line, checked);
        body.addView(toggle, new LinearLayout.LayoutParams(MATCH, WRAP));
        return toggle;
    }

    private void chatExtensions(LinearLayout body) {
        var search = searchProvider(providers.choice());
        var image = imageToolRequest();
        sectionTitle(body, "聊天工具");
        var webToggle = settingSwitch(body, "模型联网搜索", prefs.getBoolean("chat_web", false));
        webToggle.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("chat_web", checked).apply());
        body.addView(text(search == null ? "未配置官方 DeepSeek API Key" : "DeepSeek · 发送检索词，可能单独计费", 13, muted, false));
        if (search == null) {
            var connect = action("服务商连接", R.drawable.ic_link, 0, ink, tint);
            connect.setOnClickListener(v -> go("providers"));
            body.addView(connect, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        var bing = action("在浏览器打开 Bing", R.drawable.ic_search, 0, ink, tint);
        bing.setOnClickListener(v -> openBing(""));
        body.addView(bing, new LinearLayout.LayoutParams(WRAP, WRAP));
        rule(body);
        var imageToggle = settingSwitch(body, "模型生成图片", prefs.getBoolean("chat_image", false));
        imageToggle.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("chat_image", checked).apply());
        body.addView(text(image == null ? "未配置图片服务商" : image.provider().label + " · " + image.model()
                + "\n发送模型生成的图像描述，每张图可能单独计费。", 13, muted, false));
        var configure = action("图片服务商", R.drawable.ic_image, 0, ink, tint);
        configure.setOnClickListener(v -> configureImageTool());
        body.addView(configure, new LinearLayout.LayoutParams(WRAP, WRAP));
        rule(body);
        addRow(body, "记忆库", prefs.getBoolean("chat_memory", false) ? "主动记忆已开启" : "主动记忆已关闭",
                R.drawable.ic_archive, () -> go(MEMORIES));
        rule(body);
        sectionTitle(body, "对话增强");
        MaterialSwitch compact = settingSwitch(body, "自动压缩上下文", prefs.getBoolean("auto_compact", false));
        body.addView(text("长对话可能向当前服务商发出额外的摘要请求。", 13, muted, false));
        compact.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("auto_compact", checked).apply());
        space(body, 10);
        MaterialSwitch route = settingSwitch(body, "自动模型路由", autoRoute());
        body.addView(text("只在当前服务商内选模型。启用聊天工具时，优先选用支持函数调用的模型。", 13, muted, false));
        route.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("auto_route", checked).apply());
        rule(body);
        MaterialSwitch cards = settingSwitch(body, "交互卡片", prefs.getBoolean("chat_cards", false));
        body.addView(text("需要当前模型和服务商支持函数调用。卡片选择仅保存在本机，加入草稿后仍需手动发送。", 13, muted, false));
        cards.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("chat_cards", checked).apply());
    }

    private void appearance(LinearLayout body) {
        sectionTitle(body, "主题");
        MaterialButtonToggleGroup options = new MaterialButtonToggleGroup(controls);
        options.setSingleSelection(true);
        options.setSelectionRequired(true);
        String[] names = {"跟随系统", "浅色", "深色"};
        String[] values = {"system", "light", "dark"};
        int[] ids = new int[values.length];
        int checkedId = View.NO_ID;
        int[][] checkedStates = {{android.R.attr.state_checked}, {}};
        var fills = new ColorStateList(checkedStates, new int[]{accent, tint});
        var labels = new ColorStateList(checkedStates, new int[]{onAccent, ink});
        for (int i = 0; i < names.length; i++) {
            MaterialButton option = action(names[i], 0, 0, ink, tint);
            option.setId(ids[i] = View.generateViewId());
            option.setPaddingRelative(dp(4), dp(6), dp(4), dp(6));
            option.setMinWidth(0);
            option.setMinimumWidth(0);
            option.setBackgroundTintList(fills);
            option.setTextColor(labels);
            options.addView(option, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (values[i].equals(prefs.getString("theme", "system"))) checkedId = ids[i];
        }
        options.check(checkedId == View.NO_ID ? ids[0] : checkedId);
        options.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) {
                setAppearance("theme", values[i]);
                return;
            }
        });
        body.addView(options);
        space(body, 12);
        var presets = Appearance.Preset.values();
        String[] styles = new String[presets.length];
        for (int i = 0; i < presets.length; i++) styles[i] = presets[i].label;
        dropdown(body, "主题预设", styles, appearancePreset().ordinal(),
                index -> setAppearance("appearance_preset", presets[index].name()));
        addRow(body, "自定义配色", "底色、强调色、气泡、输入框与正文", R.drawable.ic_sun, this::editAppearanceColors);
        space(body, 14);
        sectionTitle(body, "聊天背景");
        String background = backgroundMode();
        dropdown(body, "背景", new String[]{"纯色", "主题渐变", "本机图片"},
                background.equals("image") ? 2 : background.equals("wash") ? 1 : 0, index -> {
                    if (index == 2 && !Wallpaper.file(this).isFile()) pickWallpaper();
                    else setAppearance("background", new String[]{"plain", "wash", "image"}[index]);
                });
        if (background.equals("image")) {
            addRow(body, "更换本机背景图", "", R.drawable.ic_file, this::pickWallpaper);
            dropdown(body, "图片填充", new String[]{"铺满", "完整显示"}, wallpaperContain() ? 1 : 0,
                    index -> setAppearance("wallpaper_fit", index == 1 ? "contain" : "cover"));
            appearanceSlider(body, "壁纸不透明度", "wallpaper_opacity", wallpaperOpacity(), 0, 100, "%");
            appearanceSlider(body, "壁纸模糊", "wallpaper_blur", wallpaperBlur(), 0, 30, " dp");
        }
        if (Wallpaper.file(this).isFile()) {
            addRow(body, "移除本机背景图", "", R.drawable.ic_close, () -> {
                if (appearanceImportBusy()) return;
                if (!Wallpaper.file(this).delete()) { notice("无法移除背景图"); return; }
                wallpaper = null;
                if (backgroundMode().equals("image")) prefs.edit().remove("background").apply();
                render();
            });
        }
        space(body, 14);
        sectionTitle(body, "材质与排版");
        String material = prefs.getString("material", "solid");
        String[] materials = {"solid", "flat", "glass"};
        dropdown(body, "聊天材质", new String[]{"实色", "扁平", "磨砂"},
                material.equals("glass") ? 2 : material.equals("flat") ? 1 : 0,
                index -> setAppearance("material", materials[index]));
        if (glassMaterial()) appearanceSlider(body, "表面不透明度", "surface_opacity", surfaceOpacity(), 70, 100, "%");
        appearanceSlider(body, "正文字号", "chat_text_size", chatTextSize(), 12, 24, " sp");
        appearanceSlider(body, "消息圆角", "message_radius", messageRadius(), 0, 32, " dp");
        appearanceSlider(body, "输入框圆角", "composer_radius", composerRadius(), 0, 40, " dp");
        String[] fonts = {"system", "serif", "monospace", "local"};
        int currentFont = switch (prefs.getString("chat_font", "system")) {
            case "serif" -> 1;
            case "monospace" -> 2;
            case "local" -> LocalChatFont.file(this).isFile() ? 3 : 0;
            default -> 0;
        };
        dropdown(body, "聊天字体", new String[]{"系统默认", "衬线", "等宽", "本机字体"}, currentFont, index -> {
            if (index == 3 && !LocalChatFont.file(this).isFile()) pickChatFont();
            else setAppearance("chat_font", fonts[index]);
        });
        if (LocalChatFont.file(this).isFile()) {
            addRow(body, "更换本机字体", "TTF / OTF", R.drawable.ic_file, this::pickChatFont);
            addRow(body, "移除本机字体", "", R.drawable.ic_close, () -> {
                if (appearanceImportBusy()) return;
                if (!LocalChatFont.file(this).delete()) { notice("无法移除本机字体"); return; }
                importedFont = null;
                if (currentFont == 3) prefs.edit().remove("chat_font").apply();
                render();
            });
        }
        space(body, 14);
        sectionTitle(body, "动效与手势");
        MaterialSwitch reduce = settingSwitch(body, "减少动效", prefs.getBoolean("reduce_motion", false));
        reduce.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("reduce_motion", checked).apply());
        MaterialSwitch swipe = settingSwitch(body, "侧栏滑动手势", prefs.getBoolean("drawer_swipe", true));
        var glyph = controls.getDrawable(R.drawable.ic_sidebar_swipe).mutate();
        glyph.setTint(ink);
        swipe.setCompoundDrawablePadding(dp(8));
        swipe.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null);
        swipe.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("drawer_swipe", checked).apply());
        addRow(body, "恢复默认外观", "", R.drawable.ic_refresh, this::confirmAppearanceReset);
    }

    private void appearanceSlider(LinearLayout body, String label, String key, int current, int min, int max, String unit) {
        TextView caption = text(label + " · " + current + unit, 14, ink, false);
        body.addView(caption);
        Slider slider = UiKit.slider(controls, controlAccent(), current - min, max - min);
        slider.setContentDescription(label);
        slider.setLabelFormatter(value -> (Math.round(value) + min) + unit);
        slider.addOnChangeListener((view, value, fromUser) -> {
            if (!fromUser) return;
            int selection = Math.round(value) + min;
            prefs.edit().putInt(key, selection).apply();
            caption.setText(label + " · " + selection + unit);
            if (updateAppearancePreview != null) updateAppearancePreview.run();
        });
        body.addView(slider, new LinearLayout.LayoutParams(MATCH, dp(52)));
    }

    private void addAppearancePreview() {
        RelativeLayout frame = new RelativeLayout(this);
        frame.setClipToOutline(true);
        frame.setBackground(shape(paper, 14, line));
        var position = new LinearLayout.LayoutParams(MATCH, WRAP);
        position.setMargins(dp(20), 0, dp(20), dp(6));
        screen.addView(frame, position);

        LinearLayout sample = column();
        sample.setId(View.generateViewId());
        sample.setPadding(dp(12), dp(6), dp(12), dp(9));
        FrameLayout backdrop = new FrameLayout(this);
        var backdropPosition = new RelativeLayout.LayoutParams(MATCH, 0);
        backdropPosition.addRule(RelativeLayout.ALIGN_TOP, sample.getId());
        backdropPosition.addRule(RelativeLayout.ALIGN_BOTTOM, sample.getId());
        frame.addView(backdrop, backdropPosition);
        ImageView image = setChatBackground(backdrop);
        frame.addView(sample, new RelativeLayout.LayoutParams(MATCH, WRAP));
        TextView prompt = text("一起整理今天的计划", 16, ink, false);
        prompt.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams bubble = new LinearLayout.LayoutParams(WRAP, WRAP);
        bubble.gravity = Gravity.END;
        sample.addView(prompt, bubble);

        TextView reply = text("先从最重要的一件事开始。", 16, ink, false);
        reply.setPadding(dp(8), dp(5), dp(8), dp(5));
        sample.addView(reply, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout composer = row();
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(3), dp(6), dp(3));
        TextView hint = text("发消息", 16, muted, false);
        composer.addView(hint, new LinearLayout.LayoutParams(0, WRAP, 1));
        ImageView send = icon(R.drawable.ic_send, 20, onAccent);
        send.setPadding(dp(5), dp(5), dp(5), dp(5));
        send.setBackground(shape(accent, 15, 0));
        composer.addView(send, new LinearLayout.LayoutParams(dp(30), dp(30)));
        sample.addView(composer, new LinearLayout.LayoutParams(MATCH, WRAP));
        for (TextView label : new TextView[]{prompt, reply, hint}) {
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
        updateAppearancePreview = () -> {
            if (image != null) applyWallpaperEffects(image);
            int fill = messageFill();
            prompt.setBackground(shape(fill, messageRadius(), 0));
            prompt.setTextColor(chatInk(fill));
            reply.setTextColor(chatInk(paper));
            reply.setBackground(visibleWallpaper() ? shape(paper, 8, 0) : null);
            int composerFill = composerFill();
            int foreground = chatInk(composerFill);
            composer.setBackground(shape(chatSurface(composerFill, foreground), composerRadius(), line));
            hint.setTextColor(foreground);
            Typeface font = chatFont();
            for (TextView label : new TextView[]{prompt, reply, hint}) {
                label.setTextSize(chatTextSize());
                label.setTypeface(font);
            }
        };
        updateAppearancePreview.run();
    }

    private void dropdown(LinearLayout body, String label, String[] values, int selected,
                          java.util.function.IntConsumer choose) {
        UiKit.dropdown(controls, body, label, values, selected, ink, muted, line, controlAccent(),
                panel, tint, choose);
    }

    private void setAppearance(String name, String value) {
        prefs.edit().putString(name, value).apply();
        render();
    }

    private void editAppearanceColors() {
        LinearLayout form = dialogBody();
        form.addView(text("使用 #RRGGBB，留空使用预设。正文会根据背景保持可读。", 14, muted, false));
        String[] keys = {"theme_base", "theme_accent", "message_color", "composer_color", "chat_text_color"};
        String[] labels = {"界面底色", "强调色", "消息气泡", "输入框", "聊天正文"};
        EditText[] inputs = new EditText[keys.length];
        for (int i = 0; i < keys.length; i++) inputs[i] = field(form, labels[i], prefs.getString(keys[i], ""), false);
        saveForm("自定义配色", form, false, () -> {
            SharedPreferences.Editor edit = prefs.edit();
            for (int i = 0; i < keys.length; i++) {
                String hex = Appearance.colorInput(inputs[i].getText().toString());
                if (hex.isEmpty()) edit.remove(keys[i]);
                else edit.putString(keys[i], hex);
            }
            edit.apply();
            render();
        }, null);
    }

    private void confirmAppearanceReset() {
        if (appearanceImportBusy()) return;
        showDialog(dialogBuilder().setTitle("恢复默认外观？")
                .setMessage("主题、聊天背景、材质和排版将恢复默认。已导入的图片和字体可再次选择。")
                .setNegativeButton("取消", null).setPositiveButton("恢复", (dialog, index) -> {
                    var edit = prefs.edit();
                    for (String key : new String[]{"theme", "appearance_preset", "theme_base", "theme_accent", "background",
                            "wallpaper_fit", "wallpaper_opacity", "wallpaper_blur", "material", "surface_opacity", "chat_font",
                            "chat_text_size", "chat_text_color", "message_radius", "message_color", "composer_radius", "composer_color"}) {
                        edit.remove(key);
                    }
                    edit.apply();
                    if (appearanceScroll != null) appearanceScroll.scrollTo(0, 0);
                    render();
                }).create(), false);
    }

    private void pickWallpaper() {
        if (appearanceImportBusy()) return;
        Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        if (picker.resolveActivity(getPackageManager()) == null) {
            picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        }
        try { startActivityForResult(picker, PICK_BACKGROUND); }
        catch (android.content.ActivityNotFoundException error) { notice("找不到系统图片选择器"); }
    }

    private void pickChatFont() {
        if (appearanceImportBusy()) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(picker, PICK_CHAT_FONT); }
        catch (android.content.ActivityNotFoundException | SecurityException error) { notice("找不到系统文件选择器"); }
    }

    private boolean appearanceImportBusy() {
        if (importingAppearance) notice("正在导入外观文件");
        return importingAppearance;
    }

    private void confirmConversationExport(ChatStore.Chat chat) {
        var file = ChatArtifacts.conversation(chat);
        showDialog(dialogBuilder().setTitle("导出 Markdown？")
                .setMessage("文件不会加密。只导出当前版本的已保存内容，不额外包含服务商配置、账户资料或卡片选择。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择保存位置", (dialog, index) -> chooseExport(file)).create(), true);
    }

    private void chooseExport(ChatArtifacts.Document document) {
        if (pendingExport != null) return;
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(document.mime())
                .putExtra(Intent.EXTRA_TITLE, document.fileName());
        pendingExport = document;
        try { startActivityForResult(picker, CREATE_EXPORT); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            pendingExport = null;
            notice("找不到可用的系统文件选择器");
        }
    }

    private void finishExport(int result, Intent data) {
        ChatArtifacts.Document file = pendingExport;
        pendingExport = null;
        if (result != RESULT_OK) return;
        if (file == null) { notice("导出已失效，请重新选择"); return; }
        android.net.Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) { notice("无法写入所选位置"); return; }
        var resolver = getApplicationContext().getContentResolver();
        localWork.execute(() -> {
            String status = "文件已保存";
            try {
                OutputStream stream = resolver.openOutputStream(uri, "wt");
                if (stream == null) throw new IOException("No output stream");
                try (var out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    file.content().write(out);
                }
            } catch (IOException | RuntimeException error) {
                status = "无法保存文件，请检查所选位置";
            }
            String message = status;
            ui.post(() -> { if (!isDestroyed()) notice(message); });
        });
    }

    private void chooseImageExport(GeneratedImage image) {
        if (pendingImageExport != null) return;
        var picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(image.mime()).putExtra(Intent.EXTRA_TITLE, "Serein-" + image.id().substring(0, 8) + "." + image.extension());
        pendingImageExport = image;
        try { startActivityForResult(picker, CREATE_IMAGE_EXPORT); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            pendingImageExport = null;
            notice("找不到可用的系统文件选择器");
        }
    }

    private void finishImageExport(int result, Intent data) {
        GeneratedImage image = pendingImageExport;
        pendingImageExport = null;
        if (result != RESULT_OK) return;
        android.net.Uri uri = data == null ? null : data.getData();
        if (image == null || uri == null || !"content".equals(uri.getScheme())) { notice("无法写入所选位置"); return; }
        var resolver = getApplicationContext().getContentResolver();
        localWork.execute(() -> {
            byte[] clear = null;
            String status;
            try {
                clear = images.read(image);
                try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("No output stream");
                    output.write(clear);
                }
                status = "图片已保存";
            } catch (Exception error) {
                status = "无法保存图片，请检查所选位置";
            } finally {
                if (clear != null) java.util.Arrays.fill(clear, (byte) 0);
            }
            String message = status;
            ui.post(() -> { if (!isDestroyed()) notice(message); });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_UPDATE) {
            if (appUpdate != null) appUpdate.installerReturned();
            if (result != RESULT_OK) notice("安装未完成，可重新安装");
            return;
        }
        if (request == CREATE_EXPORT) {
            finishExport(result, data);
            return;
        }
        if (request == CREATE_IMAGE_EXPORT) {
            finishImageExport(result, data);
            return;
        }
        if (request == PICK_ATTACHMENT || request == PICK_LIBRARY_FILE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                var source = data.getData();
                if (!"content".equals(source.getScheme())) { notice("无法读取所选文件"); return; }
                boolean library = request == PICK_LIBRARY_FILE;
                importFile(library, () -> files.importFile(getApplicationContext(), source, library), false);
            }
            return;
        }
        if ((request != PICK_BACKGROUND && request != PICK_CHAT_FONT) || result != RESULT_OK
                || data == null || data.getData() == null || appearanceImportBusy()) return;
        android.net.Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { notice("无法读取所选文件"); return; }
        boolean font = request == PICK_CHAT_FONT;
        importingAppearance = true;
        localWork.execute(() -> {
            try {
                if (font) LocalChatFont.importFont(this, uri);
                else Wallpaper.importImage(this, uri);
                prefs.edit().putString(font ? "chat_font" : "background", font ? "local" : "image").apply();
                runOnUiThread(() -> {
                    importingAppearance = false;
                    if (isDestroyed()) return;
                    if (font) importedFont = null;
                    else wallpaper = null;
                    render();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    importingAppearance = false;
                    if (!isDestroyed()) notice(font ? "无法导入字体，请选择 12 MiB 以内的 TTF 或 OTF 文件" : "无法导入背景图");
                });
            }
        });
    }

    private Appearance.Preset appearancePreset() {
        return Appearance.Preset.read(prefs.getString("appearance_preset", ""));
    }

    private String backgroundMode() { return prefs.getString("background", "plain"); }
    private boolean wallpaperContain() { return prefs.getString("wallpaper_fit", "cover").equals("contain"); }
    private int wallpaperOpacity() { return Appearance.clamp(prefs.getInt("wallpaper_opacity", 21), 0, 100); }
    private int wallpaperBlur() { return Appearance.clamp(prefs.getInt("wallpaper_blur", glassMaterial() ? 12 : 0), 0, 30); }
    private boolean visibleWallpaper() { return wallpaper != null && backgroundMode().equals("image") && wallpaperOpacity() > 0; }
    private boolean glassMaterial() { return prefs.getString("material", "solid").equals("glass"); }
    private int surfaceOpacity() { return Appearance.clamp(prefs.getInt("surface_opacity", 90), 70, 100); }
    private int chatTextSize() { return Appearance.clamp(prefs.getInt("chat_text_size", 16), 12, 24); }
    private int messageRadius() { return Appearance.clamp(prefs.getInt("message_radius", 18), 0, 32); }

    private int composerRadius() {
        int fallback = prefs.getString("material", "solid").equals("flat") ? 8 : 27;
        return Appearance.clamp(prefs.getInt("composer_radius", fallback), 0, 40);
    }

    private int composerFill() { return Appearance.color(prefs.getString("composer_color", ""), panel); }
    private int messageFill() { return Appearance.color(prefs.getString("message_color", ""), tint); }

    private int chatInk(int fill) {
        return Appearance.readable(Appearance.color(prefs.getString("chat_text_color", ""), ink), ink, fill);
    }

    private int chatSurface(int fill, int foreground) {
        return glassMaterial() ? Appearance.glass(fill, foreground, surfaceOpacity()) : fill;
    }

    private Typeface chatFont() {
        return switch (prefs.getString("chat_font", "system")) {
            case "serif" -> Typeface.SERIF;
            case "monospace" -> Typeface.MONOSPACE;
            case "local" -> {
                if (importedFont == null) {
                    try { importedFont = LocalChatFont.load(this); }
                    catch (RuntimeException ignored) { importedFont = Typeface.DEFAULT; }
                    if (importedFont == null) importedFont = Typeface.DEFAULT;
                }
                yield importedFont;
            }
            default -> Typeface.DEFAULT;
        };
    }

    private ImageView setChatBackground(FrameLayout target) {
        String background = backgroundMode();
        target.setBackgroundColor(paper);
        if (background.equals("wash")) {
            target.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Appearance.blend(paper, accent, .15f), paper, tint}));
        }
        if (!background.equals("image")) return null;
        if (wallpaper == null) wallpaper = BitmapFactory.decodeFile(Wallpaper.file(this).getAbsolutePath());
        if (wallpaper == null) return null;
        ImageView image = new ImageView(this);
        image.setImageBitmap(wallpaper);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        applyWallpaperEffects(image);
        target.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
        return image;
    }

    private void applyWallpaperEffects(ImageView image) {
        image.setAlpha(wallpaperOpacity() / 100f);
        image.setScaleType(wallpaperContain() ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        int blur = wallpaperBlur();
        if (!Integer.valueOf(blur).equals(image.getTag())) {
            image.setTag(blur);
            image.setRenderEffect(blur == 0 ? null : RenderEffect.createBlurEffect(dp(blur), dp(blur), Shader.TileMode.CLAMP));
        }
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

    private MaterialButton action(String label, int glyph, int endGlyph, int foreground, int background) {
        MaterialButton button = UiKit.button(controls, label, glyph, foreground, background, null);
        if (endGlyph != 0) button.setIconResource(endGlyph);
        Motion.press(button, this::motionEnabled);
        return button;
    }

    private MaterialButton iconButton(String label, int glyph, int foreground, int background, Runnable tap) {
        MaterialButton button = UiKit.iconButton(controls, label, glyph, foreground, background, tap);
        Motion.press(button, this::motionEnabled);
        return button;
    }

    private int controlAccent() { return Appearance.contrast(accent, panel) >= 3 ? accent : ink; }

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
        return UiKit.surface(controls == null ? this : controls, fill, radius, border);
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
