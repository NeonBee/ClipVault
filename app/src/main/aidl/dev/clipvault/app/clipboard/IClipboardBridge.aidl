package dev.clipvault.app.clipboard;

import dev.clipvault.app.clipboard.IClipboardListener;

// Protocol v3. Clipboard-only surface: no shell, file or generic system-service methods.
interface IClipboardBridge {
    void destroy() = 16777114;
    // Returns null for an empty clipboard or on error. error[0] receives this call's error code in
    // the same transaction, so concurrent listener-triggered reads cannot overwrite it.
    String readText(out int[] error) = 1;
    int protocolVersion() = 2;
    boolean registerListener(IClipboardListener listener) = 3;
    void unregisterListener(IClipboardListener listener) = 4;
    // Resolves the hidden API, tries a real read and listener registration, returns CAP_* bits.
    // error[0] receives the probe's own error code.
    int probeCapabilities(out int[] error) = 5;
    // Diagnostics only: error code of the most recent operation from any thread. Never content.
    int lastErrorCode() = 6;
}
