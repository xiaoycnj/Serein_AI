package app.serein.chat;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LocalWriteCheck {
    private static final String EMPTY_HISTORY = "{\"history\":[],\"active\":\"\"}";

    public static void main(String[] args) throws Exception {
        slowChatWriteAndReload();
        failedWriteKeepsCommittedState();
        System.out.println("PASS: blocked writes leave UI state readable, publish only after success, and reload in order");
    }

    private static void slowChatWriteAndReload() throws Exception {
        var initial = new AtomicReference<>(EMPTY_HISTORY);
        var seed = new ChatStore(initial.get(), initial::set);
        seed.append(-1, "first", "kept", "Serein", null).commit();

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disk = new AtomicReference<>(initial.get());
        var owner = Thread.currentThread();
        var store = new ChatStore(disk.get(), saved -> {
            equal(Thread.currentThread() == owner, false);
            entered.countDown();
            try { equal(release.await(5, TimeUnit.SECONDS), true); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            disk.set(saved);
        });
        var chat = store.active;
        String full = "A line of normal text in a long model reply.\n".repeat(3_200) + "Fixture complete.";
        var change = store.append(0, "second", full, "Serein", null, null, ChatHistory.ReplyState.STOPPED);
        var main = new LinkedBlockingQueue<Runnable>();
        var write = new LocalWrite();
        var io = Executors.newSingleThreadExecutor();
        try {
            write.start(change, io, main::add, saved -> {
                equal(Thread.currentThread(), owner);
                equal(saved, true);
                change.publish().run();
            });
            equal(entered.await(5, TimeUnit.SECONDS), true);
            equal(store.active, chat);
            equal(chat.history.path(chat.history.leaf()), List.of(0));
            equal(disk.get(), initial.get());
            equal(main.isEmpty(), true);
            // A replacement Activity queues its load behind the pending write, even before the UI callback runs.
            var reload = io.submit(() -> {
                equal(write.saved(), true);
                return new ChatStore(disk.get(), ignored -> {});
            });
            release.countDown();
            var reopened = reload.get(5, TimeUnit.SECONDS);
            equal(reopened.active.history.path(reopened.active.history.leaf()), List.of(0, 1));
            equal(reopened.active.history.turn(1).reply(), full);
            equal(reopened.active.history.turn(1).replyState(), ChatHistory.ReplyState.STOPPED);
            equal(chat.history.leaf(), 0);
            var notify = main.poll(5, TimeUnit.SECONDS);
            if (notify == null) throw new AssertionError("Missing save callback");
            notify.run();
            equal(chat.history.leaf(), 1);
            equal(chat.history.turn(1).reply(), full);
        } finally {
            release.countDown();
            io.shutdownNow();
        }
    }

    private static void failedWriteKeepsCommittedState() {
        var saved = new AtomicReference<>(EMPTY_HISTORY);
        var fail = new AtomicBoolean(false);
        var store = new ChatStore(saved.get(), value -> {
            if (fail.get()) throw new IllegalStateException("Disk write failed");
            saved.set(value);
        });
        store.append(-1, "first", "kept", "Serein", null).commit();
        var chat = store.active;
        String before = saved.get();
        fail.set(true);

        var failed = store.append(0, "unsaved", "uncommitted reply", "Serein", null);
        var write = new LocalWrite();
        var notified = new AtomicBoolean();
        write.start(failed, Runnable::run, Runnable::run, success -> {
            equal(success, false);
            notified.set(true);
        });
        equal(notified.get(), true);
        equal(write.saved(), false);
        equal(store.active, chat);
        equal(chat.history.path(chat.history.leaf()), List.of(0));
        equal(saved.get(), before);

        fail.set(false);
        store.append(0, "retry", "saved", "Serein", null).commit();
        var restored = new ChatStore(saved.get(), ignored -> {});
        equal(restored.active.history.path(restored.active.history.leaf()), List.of(0, 1));
        equal(restored.active.history.turn(1).prompt(), "retry");
    }

    private static void equal(Object value, Object expected) {
        if (!Objects.equals(value, expected)) throw new AssertionError("Expected " + expected + " but got " + value);
    }
}
