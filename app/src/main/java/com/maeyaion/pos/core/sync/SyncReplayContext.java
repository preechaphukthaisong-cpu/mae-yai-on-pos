package com.maeyaion.pos.core.sync;

/** Thread-scoped marker preventing remote replay from generating new local outbox echoes. */
public final class SyncReplayContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private SyncReplayContext() {}

    public static boolean isApplyingRemote() {
        return DEPTH.get() > 0;
    }

    public static Scope enter() {
        DEPTH.set(DEPTH.get() + 1);
        return new Scope();
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override public void close() {
            if (closed) return;
            closed = true;
            int depth = DEPTH.get();
            if (depth <= 1) DEPTH.remove();
            else DEPTH.set(depth - 1);
        }
    }
}
