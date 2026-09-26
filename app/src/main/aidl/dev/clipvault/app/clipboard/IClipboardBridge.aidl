package dev.clipvault.app.clipboard;

import dev.clipvault.app.clipboard.IClipboardListener;

// Protocol v3. Clipboard-only surface: no shell, file or generic system-service methods.
interface IClipboardBridge {
    void destroy() = 16777114;
    // Returns null for an empty clipboard or on error; lastErrorCode() tells them apart.
    String readText() = 1;
    int protocolVersion() = 2;
    boolean registerListener(IClipboardListener listener) = 3;
    void unregisterListener(IClipboardListener listener) = 4;
    // Resolves the hidden API, tries a real read and listener registration, returns CAP_* bits.
    int probeCapabilities() = 5;
    // ClipboardBridgeProtocol error code of the last operation. Never contains clipboard data.
    int lastErrorCode() = 6;
}
