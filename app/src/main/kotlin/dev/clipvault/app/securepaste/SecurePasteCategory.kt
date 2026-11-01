package dev.clipvault.app.securepaste

import dev.clipvault.app.R
import dev.clipvault.app.nativecore.NativeClassifier

/**
 * Conservative WP-08A folders over the EXISTING persisted classifier flags.
 *
 * An item can appear in more than one folder (e.g. a token-looking URL can be
 * both Sensitive and Links). These are browse categories, NOT security labels
 * or authority to downgrade sensitive-capture policy.
 *
 * New type signals come from Java BrowseClassifier augmentation shared by the
 * JNI and Java fallback paths; auto-capture skip-sensitive is unchanged.
 */
internal enum class SecurePasteCategory(
    val label: Int,
    val glyph: String,
    val requiredFlags: Int = 0,
    val excludedFlags: Int = 0,
) {
    ALL(R.string.secure_paste_folder_all, "▤"),
    CREDENTIALS(R.string.secure_paste_folder_credentials, "⚿", NativeClassifier.CREDENTIAL),
    SENSITIVE(R.string.secure_paste_folder_sensitive, "🔐", NativeClassifier.SENSITIVE),
    FINANCE(R.string.secure_paste_folder_finance, "▣", NativeClassifier.FINANCE),
    IDENTITY(R.string.secure_paste_folder_identity, "♙", NativeClassifier.IDENTITY),
    DEVELOPMENT(
        R.string.secure_paste_folder_development, "⌘",
        NativeClassifier.CODE or NativeClassifier.JSON or NativeClassifier.GITHUB or
            NativeClassifier.SSH_COMMAND,
    ),
    LINKS(R.string.secure_paste_folder_links, "↗", NativeClassifier.LINK),
    CONTACT(R.string.secure_paste_folder_contact, "@",
        NativeClassifier.EMAIL or NativeClassifier.PHONE),
    NOTES(R.string.secure_paste_folder_notes, "≡", NativeClassifier.LONG_TEXT),
    UNCLASSIFIED(
        R.string.secure_paste_folder_unclassified, "?",
        excludedFlags = NativeClassifier.CREDENTIAL or NativeClassifier.SENSITIVE or
            NativeClassifier.FINANCE or NativeClassifier.IDENTITY or
            NativeClassifier.CODE or NativeClassifier.JSON or NativeClassifier.GITHUB or
            NativeClassifier.SSH_COMMAND or NativeClassifier.LINK or NativeClassifier.EMAIL or
            NativeClassifier.PHONE or NativeClassifier.LONG_TEXT,
    );

    fun matches(flags: Int): Boolean =
        (requiredFlags == 0 || (flags and requiredFlags) != 0) &&
            (excludedFlags == 0 || (flags and excludedFlags) == 0)

    companion object {
        fun iconFor(flags: Int, pinned: Boolean): String {
            if (pinned) return "★"
            return entries.firstOrNull { it != ALL && it != UNCLASSIFIED &&
                it.matches(flags) }?.glyph ?: "▤"
        }
    }
}
