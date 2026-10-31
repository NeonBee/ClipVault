package dev.clipvault.app.securepaste

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethod
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CI emulator only: do not run connected instrumentation on the user's populated phone. */
@RunWith(AndroidJUnit4::class)
class SecurePasteImeManifestTest {
    @Test fun systemCanDiscoverProtectedImeButNotUnlockActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val service = pm.getServiceInfo(
            ComponentName(context, SecurePasteImeService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(service.exported)
        assertEquals(Manifest.permission.BIND_INPUT_METHOD, service.permission)
        assertNotNull(service.metaData)
        assertTrue(service.metaData.getInt(InputMethod.SERVICE_META_DATA) != 0)

        val unlock = pm.getActivityInfo(
            ComponentName(context, SecurePasteUnlockActivity::class.java),
            0,
        )
        assertFalse(unlock.exported)
        // The foreground FragmentActivity is visually transparent. BiometricPrompt still
        // owns the actual strong-biometric prompt; it is not moved into IME service.
        assertEquals(dev.clipvault.app.R.style.Theme_ClipVault_SecureImeUnlock, unlock.theme)
    }
}
