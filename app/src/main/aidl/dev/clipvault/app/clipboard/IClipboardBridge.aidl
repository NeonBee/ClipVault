package dev.clipvault.app.clipboard;

interface IClipboardBridge {
    void destroy() = 16777114;
    String readText() = 1;
    int protocolVersion() = 2;
}
