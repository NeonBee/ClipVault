package dev.clipvault.app.clipboard;

import androidx.annotation.Nullable;

/** Small injectable boundary used by the capture loop and fake CI bridges. */
public interface ClipboardBridge {
    boolean isReady();
    boolean isEventDriven();
    @Nullable String readText();
}
