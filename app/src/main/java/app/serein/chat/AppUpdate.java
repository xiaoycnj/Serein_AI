package app.serein.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One update operation that can outlive the update screen and configuration changes. */
final class AppUpdate {
    enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALLER, ERROR }

    record State(Phase phase, SereinUpdates.Release release, String version, long received, String error) {
        boolean busy() { return phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING; }

        int percent() { return release == null ? 0 : (int) Math.min(100, received * 100 / release.bytes()); }
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private State state = state(Phase.IDLE);
    private Consumer<State> observer;
    private ModelApi.Cancellation download;
    private boolean closed;

    AppUpdate(Context context) { this.context = context.getApplicationContext(); }

    State current() { return state; }

    void observe(Consumer<State> observer) { this.observer = observer; }

    void check() {
        if (state.busy() || state.phase() == Phase.INSTALLER) return;
        set(state(Phase.CHECKING));
        worker.execute(() -> {
            var file = SereinUpdates.apk(context);
            if (file.isFile()) {
                try {
                    String version = SereinUpdates.verifyCachedApk(context).versionName;
                    post(new State(Phase.READY, null, version, 0, null));
                    return;
                } catch (Exception invalid) {
                    // A cache from an older or differently signed build cannot be installed.
                }
            }
            try {
                var release = SereinUpdates.latest(context);
                post(release == null ? state(Phase.CURRENT) : forRelease(Phase.AVAILABLE, release, 0, null));
            } catch (Exception error) {
                post(new State(Phase.ERROR, null, null, 0, "无法读取 GitHub 发布信息，请稍后重试。"));
            }
        });
    }

    void start() {
        var release = state.release();
        if (release == null || state.busy() || state.phase() == Phase.INSTALLER) return;
        var cancellation = new ModelApi.Cancellation();
        download = cancellation;
        set(forRelease(Phase.DOWNLOADING, release, 0, null));
        worker.execute(() -> {
            try {
                SereinUpdates.download(context, release, cancellation, new SereinUpdates.Progress() {
                    @Override public void downloaded(long bytes) { post(forRelease(Phase.DOWNLOADING, release, bytes, null)); }
                    @Override public void verifying() { post(forRelease(Phase.VERIFYING, release, release.bytes(), null)); }
                });
                post(forRelease(Phase.READY, release, release.bytes(), null));
            } catch (Exception error) {
                post(forRelease(Phase.ERROR, release, 0, errorMessage(error, "无法下载更新，请稍后重试。")));
            }
        });
    }

    void handOff() {
        if (state.phase() == Phase.READY) set(new State(Phase.INSTALLER, state.release(), state.version(), state.received(), null));
    }

    void verifyForInstall(Runnable onVerified) {
        var ready = state;
        if (ready.phase() != Phase.READY) return;
        set(new State(Phase.VERIFYING, ready.release(), ready.version(), ready.received(), null));
        worker.execute(() -> {
            try {
                SereinUpdates.verifyCachedApk(context);
                main.post(() -> {
                    if (closed) return;
                    set(new State(Phase.READY, ready.release(), ready.version(), ready.received(), null));
                    onVerified.run();
                });
            } catch (Exception error) {
                post(new State(Phase.ERROR, ready.release(), ready.version(), 0,
                        errorMessage(error, "无法读取更新包，请重新下载。")));
            }
        });
    }

    void installerReturned() {
        if (state.phase() == Phase.INSTALLER) set(new State(Phase.READY, state.release(), state.version(), state.received(), null));
    }

    void installationFailed(String message) {
        set(new State(Phase.READY, state.release(), state.version(), state.received(), message));
    }

    void close() {
        closed = true;
        if (download != null) new Thread(download.cancel(), "serein-stop-update").start();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    private void post(State next) { main.post(() -> { if (!closed) set(next); }); }

    private void set(State next) {
        state = next;
        if (!next.busy()) download = null;
        if (observer != null) observer.accept(next);
    }

    private static State state(Phase phase) { return new State(phase, null, null, 0, null); }

    private static State forRelease(Phase phase, SereinUpdates.Release release, long received, String error) {
        return new State(phase, release, release.version(), received, error);
    }

    static String errorMessage(Exception error, String fallback) {
        String message = error.getMessage();
        return error instanceof IOException && message != null && message.startsWith("APK") ? message : fallback;
    }
}
