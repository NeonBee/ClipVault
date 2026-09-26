package dev.clipvault.app.clipboard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Deduplicates bridge events, enforces the bridge payload limit and owns the adaptive polling
 * cadence without logging clipboard data.
 */
public final class ClipboardCaptureCoordinator {
    public interface Sink { void capture(@NonNull String text); }

    /** Receives sanitized rejection reasons only, never the rejected text. */
    public interface RejectionListener { void onRejected(int errorCode); }

    private final Sink sink;
    @Nullable private final RejectionListener rejectionListener;
    private String lastText;
    private int lastRejectedLength = -1;
    private long pollingDelayMs = 500L;

    public ClipboardCaptureCoordinator(@NonNull Sink sink) {
        this(sink, null);
    }

    public ClipboardCaptureCoordinator(@NonNull Sink sink, @Nullable RejectionListener rejectionListener) {
        this.sink = sink;
        this.rejectionListener = rejectionListener;
    }

    public synchronized boolean accept(@Nullable String text) {
        if (text == null || text.trim().isEmpty() || text.equals(lastText)) return false;
        if (ClipboardBridgeProtocol.exceedsPayloadLimit(text)) {
            // Explicit rejection, never silent truncation. Report each oversized clip once.
            if (text.length() != lastRejectedLength && rejectionListener != null) {
                rejectionListener.onRejected(ClipboardBridgeProtocol.TRANSACTION_TOO_LARGE);
            }
            lastRejectedLength = text.length();
            return false;
        }
        lastText = text;
        lastRejectedLength = -1;
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
