package dev.clipvault.app.securepaste

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePasteTransportTest {
    private fun permitted(type: Int = InputType.TYPE_CLASS_TEXT, epoch: Long = 12,
                          currentEpoch: Long = 12, vaultOpen: Boolean = true,
                          deviceLocked: Boolean = false, editor: String? = "example.editor",
                          expected: String? = "example.editor", inserting: Boolean = false,
                          ownPackage: String? = "dev.clipvault.app.debug") =
        SecurePasteTransport.permitted(
            vaultOpen, epoch, currentEpoch, deviceLocked, editor, expected, type, inserting, ownPackage,
        )

    @Test fun allowsOrdinaryTextIncludingUrlAndMultilineTypes() {
        assertTrue(permitted())
        assertTrue(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertTrue(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test fun failsClosedForLockEpochEditorAndConcurrentTap() {
        assertFalse(permitted(vaultOpen = false))
        assertFalse(permitted(currentEpoch = 13))
        assertFalse(permitted(deviceLocked = true))
        assertFalse(permitted(editor = null))
        assertFalse(permitted(editor = "other.app"))
        assertFalse(permitted(expected = null))
        assertFalse(permitted(inserting = true))
        assertFalse(permitted(ownPackage = "example.editor"))
    }

    @Test fun rejectsNullNumericPhoneDateAndPasswordFields() {
        assertFalse(permitted(EditorInfo.TYPE_NULL))
        assertFalse(permitted(InputType.TYPE_CLASS_NUMBER))
        assertFalse(permitted(InputType.TYPE_CLASS_PHONE))
        assertFalse(permitted(InputType.TYPE_CLASS_DATETIME))
        assertFalse(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test fun directInsertCallsOnlyCommitTextOnceWithCursorAfterPlaintext() {
        var calls = 0
        val text = "한글\nemoji 🦊 and code = 1"
        val connection = fake { contents, cursor ->
            calls++
            assertEquals(text, contents)
            assertEquals(1, cursor)
            true
        }
        assertTrue(SecurePasteTransport.commit(connection, text))
        assertEquals(1, calls)
    }

    @Test fun rejectedMissingOrThrowingConnectionNeverRetriesThroughClipboard() {
        var calls = 0
        val rejecting = fake { _, _ -> calls++; false }
        assertFalse(SecurePasteTransport.commit(rejecting, "private"))
        assertEquals(1, calls)
        assertFalse(SecurePasteTransport.commit(null, "private"))
        assertFalse(SecurePasteTransport.commit(rejecting, ""))
        assertEquals(1, calls)
        val throwing = fake { _, _ -> throw IllegalStateException("stale editor") }
        assertFalse(SecurePasteTransport.commit(throwing, "private"))
    }

    private fun fake(commit: (String, Int) -> Boolean): InputConnection =
        Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            if (method.name == "commitText") {
                commit(args!![0] as String, args[1] as Int)
            } else {
                false
            }
        } as InputConnection
}
