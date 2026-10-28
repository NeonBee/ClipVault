package dev.clipvault.app.quickpaste

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.clipvault.app.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** WP-06S: the optional launcher alias must not expose the original QuickPaste activity. */
@RunWith(AndroidJUnit4::class)
class QuickPasteLauncherEntryTest {
    @Test fun aliasIsExportedOnlyThroughExplicitOptInAndTargetRemainsPrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val alias = QuickPasteLauncherEntry.component(context)
        val previous = pm.getComponentEnabledSetting(alias)

        try {
            val info = pm.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(QuickPasteActivity::class.java.name, info.targetActivity)
            assertTrue(info.exported)

            val target = pm.getActivityInfo(
                ComponentName(context, QuickPasteActivity::class.java),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertFalse(target.exported)

            QuickPasteLauncherEntry.setEnabled(context, false)
            assertFalse(QuickPasteLauncherEntry.isEnabled(context))
            // Android's generic package launch must still open Main while INFO alias is disabled.
            assertEquals(
                MainActivity::class.java.name,
                pm.getLaunchIntentForPackage(context.packageName)?.component?.className,
            )

            QuickPasteLauncherEntry.setEnabled(context, true)
            assertTrue(QuickPasteLauncherEntry.isEnabled(context))
            // This is a PackageManager API behavior test, not proof that Samsung DeX uses it.
            // The MAIN/INFO alias should take precedence over MAIN/LAUNCHER when enabled.
            assertEquals(
                alias.className,
                pm.getLaunchIntentForPackage(context.packageName)?.component?.className,
            )

            QuickPasteLauncherEntry.setEnabled(context, false)
            assertFalse(QuickPasteLauncherEntry.isEnabled(context))
            assertEquals(
                MainActivity::class.java.name,
                pm.getLaunchIntentForPackage(context.packageName)?.component?.className,
            )
        } finally {
            // On a disposable CI emulator this is DEFAULT. Restore the existing app setting
            // anyway, so tests remain safe if this test is invoked on a personal device.
            pm.setComponentEnabledSetting(alias, previous, PackageManager.DONT_KILL_APP)
        }
    }
}
