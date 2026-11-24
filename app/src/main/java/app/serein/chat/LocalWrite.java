package app.serein.chat;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class LocalWrite {
    record Change(Runnable write, Runnable publish) {
        static final Change NONE = new Change(() -> {}, () -> {});

        void commit() {
            write.run();
            publish.run();
        }
    }

    private volatile boolean saved;

    // Startup reads use the same disk executor, so a replacement Activity sees the final result.
    boolean saved() { return saved; }

    void start(Change change, Executor disk, Executor main, Consumer<Boolean> callback) {
        disk.execute(() -> {
            try {
                change.write().run();
                saved = true;
            } catch (RuntimeException error) {
                saved = false;
            }
            main.execute(() -> callback.accept(saved));
        });
    }
}
