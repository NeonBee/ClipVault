package dev.clipvault.app.securepaste

import dev.clipvault.app.nativecore.NativeClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePasteCategoryTest {
    @Test fun multipleMembershipsPreserveSensitiveFlags() {
        val flags = NativeClassifier.SENSITIVE or NativeClassifier.LINK
        assertTrue(SecurePasteCategory.SENSITIVE.matches(flags))
        assertTrue(SecurePasteCategory.LINKS.matches(flags))
        assertFalse(SecurePasteCategory.UNCLASSIFIED.matches(flags))
    }

    @Test fun candidateCategoriesAreMultiLabelAndNeverClaimLowSensitivity() {
        val flags = NativeClassifier.CREDENTIAL or NativeClassifier.LINK or NativeClassifier.KOREAN
        assertTrue(SecurePasteCategory.CREDENTIALS.matches(flags))
        assertTrue(SecurePasteCategory.LINKS.matches(flags))
        assertFalse(SecurePasteCategory.UNCLASSIFIED.matches(flags))
        assertEquals("⚿", SecurePasteCategory.iconFor(flags, false))
        assertTrue(SecurePasteCategory.FINANCE.matches(NativeClassifier.FINANCE))
        assertTrue(SecurePasteCategory.IDENTITY.matches(NativeClassifier.IDENTITY))
        assertTrue(SecurePasteCategory.DEVELOPMENT.matches(NativeClassifier.SSH_COMMAND))
    }

    @Test fun unclassifiedAcceptsOtherAndLanguageOnly() {
        assertTrue(SecurePasteCategory.UNCLASSIFIED.matches(NativeClassifier.OTHER))
        assertTrue(SecurePasteCategory.UNCLASSIFIED.matches(NativeClassifier.ENGLISH))
        assertFalse(SecurePasteCategory.UNCLASSIFIED.matches(NativeClassifier.CODE))
    }

    @Test fun representativeIconUsesDeterministicPriority() {
        assertEquals("★", SecurePasteCategory.iconFor(NativeClassifier.LINK, true))
        assertEquals("🔐", SecurePasteCategory.iconFor(NativeClassifier.SENSITIVE or NativeClassifier.LINK, false))
        assertEquals("▤", SecurePasteCategory.iconFor(NativeClassifier.OTHER, false))
    }
}
