package dev.clipvault.app.clipboard;

import dev.clipvault.app.clipboard.IClipboardListener;

interface IClipboardBridge {
    void destroy() = 16777114;
    String readText() = 1;
    int protocolVersion() = 2;
    boolean registerListener(IClipboardListener listener) = 3;
    void unregisterListener(IClipboardListener listener) = 4;
}
