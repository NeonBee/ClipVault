package dev.clipvault.app.clipboard;

import androidx.annotation.NonNull;

/**
 * Runtime state of the privileged clipboard path. Binder connectivity and hidden API usability are
 * separate states, so "Shizuku is connected" never implies "clipboard capture works".
 */
public final class BridgeHealth {
    public enum State {
        NO_SHIZUKU,
        PERMISSION_REQUIRED,
        BINDER_CONNECTED,
        API_PROBING,
        READY_EVENT,
        READY_POLL,
        DEGRADED,
    }

    @NonNull public final State state;
    /** A {@link ClipboardBridgeProtocol} error code; may be non-OK while ready (e.g. one oversized clip). */
    public final int errorCode;

    private BridgeHealth(@NonNull State state, int errorCode) {
        this.state = state;
        this.errorCode = errorCode;
    }

    @NonNull
    public static BridgeHealth of(@NonNull State state) {
        return new BridgeHealth(state, ClipboardBridgeProtocol.OK);
    }

    @NonNull
    public static BridgeHealth degraded(int errorCode) {
        return new BridgeHealth(State.DEGRADED, errorCode);
    }

    /** Maps a v3 probe result. Reading is mandatory; the event listener only picks the cadence. */
    @NonNull
    public static BridgeHealth fromProbe(int capabilities, int errorCode) {
        if ((capabilities & ClipboardBridgeProtocol.CAP_READ) == 0) {
            return degraded(errorCode == ClipboardBridgeProtocol.OK
                    ? ClipboardBridgeProtocol.INVOCATION_FAILED : errorCode);
        }
        State state = (capabilities & ClipboardBridgeProtocol.CAP_EVENT_LISTENER) != 0
                ? State.READY_EVENT : State.READY_POLL;
        return new BridgeHealth(state, errorCode);
    }

    @NonNull
    public BridgeHealth withError(int code) {
        return code == errorCode ? this : new BridgeHealth(state, code);
    }

    @NonNull
    public BridgeHealth withoutEvents() {
        return state == State.READY_EVENT ? new BridgeHealth(State.READY_POLL, errorCode) : this;
    }

    public boolean isReady() {
        return state == State.READY_EVENT || state == State.READY_POLL;
    }

    public boolean isEventDriven() {
        return state == State.READY_EVENT;
    }

    /** Sanitized identifier for diagnostics: empty when healthy, never clipboard data. */
    @NonNull
    public String diagnosticCode() {
        if (isReady()) {
            return errorCode == ClipboardBridgeProtocol.TRANSACTION_TOO_LARGE ? "CAPTURE_REJECTED_TOO_LARGE" : "";
        }
        return errorCode == ClipboardBridgeProtocol.OK
                ? state.name() : state.name() + ":" + ClipboardBridgeProtocol.errorName(errorCode);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BridgeHealth)) return false;
        BridgeHealth that = (BridgeHealth) other;
        return state == that.state && errorCode == that.errorCode;
    }

    @Override
    public int hashCode() {
        return state.hashCode() * 31 + errorCode;
    }

    @NonNull
    @Override
    public String toString() {
        return state.name() + "/" + ClipboardBridgeProtocol.errorName(errorCode);
    }
}
