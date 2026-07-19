-keep class dev.clipvault.app.clipboard.PrivilegedClipboardService { *; }
-keep class dev.clipvault.app.clipboard.IClipboardBridge$Stub { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.conscrypt.**
