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
 * Expand the taxonomy only after native/fallback classifier parity and
 * auto-capture skip-sensitive policy are tested independently.
 */
internal enum class SecurePasteCategory(
    val label: Int,
    val glyph: String,
    val requiredFlags: Int = 0,
    val excludedFlags: Int = 0,
) {
    ALL(R.string.secure_paste_folder_all, "▤"),
    SENSITIVE(R.string.secure_paste_folder_sensitive, "🔐", NativeClassifier.SENSITIVE),
    DEVELOPMENT(
        R.string.secure_paste_folder_development, "⌘",
        NativeClassifier.CODE or NativeClassifier.JSON or NativeClassifier.GITHUB,
    ),
    LINKS(R.string.secure_paste_folder_links, "↗", NativeClassifier.LINK),
    CONTACT(R.string.secure_paste_folder_contact, "@",
        NativeClassifier.EMAIL or NativeClassifier.PHONE),
    NOTES(R.string.secure_paste_folder_notes, "≡", NativeClassifier.LONG_TEXT),
    UNCLASSIFIED(
        R.string.secure_paste_folder_unclassified, "?",
        excludedFlags = NativeClassifier.SENSITIVE or NativeClassifier.CODE or
            NativeClassifier.JSON or NativeClassifier.GITHUB or NativeClassifier.LINK or
            NativeClassifier.EMAIL or NativeClassifier.PHONE or NativeClassifier.LONG_TEXT,
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
