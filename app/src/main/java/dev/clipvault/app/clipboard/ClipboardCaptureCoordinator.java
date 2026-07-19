package dev.clipvault.app.clipboard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Deduplicates bridge events and owns the adaptive polling cadence without logging clipboard data. */
public final class ClipboardCaptureCoordinator {
    public interface Sink { void capture(@NonNull String text); }

    private final Sink sink;
    private String lastText;
    private long pollingDelayMs = 500L;

    public ClipboardCaptureCoordinator(@NonNull Sink sink) {
        this.sink = sink;
    }

    public synchronized boolean accept(@Nullable String text) {
        if (text == null || text.trim().isEmpty() || text.equals(lastText)) return false;
        lastText = text;
        pollingDelayMs = 500L;
        sink.capture(text);
        return true;
    }

    public synchronized long poll(@NonNull ClipboardBridge bridge) {
        boolean changed = accept(bridge.readText());
        if (bridge.isEventDriven()) {
            pollingDelayMs = 30_000L;
        } else if (bridge.isReady()) {
            pollingDelayMs = changed ? 500L : Math.min(Math.max(500L, pollingDelayMs * 2L), 5_000L);
        } else {
            pollingDelayMs = Math.min(Math.max(5_000L, pollingDelayMs * 2L), 60_000L);
        }
        return pollingDelayMs;
    }
}
