-keep class dev.clipvault.app.clipboard.PrivilegedClipboardService { *; }
-keep class dev.clipvault.app.clipboard.IClipboardBridge$Stub { *; }
-keep class dev.clipvault.app.clipboard.IClipboardListener$Stub { *; }
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.conscrypt.**
