package dev.clipvault.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.clipvault.app.ThemeTestActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A long diagnostic value must not squeeze its label into a one-character column. */
@RunWith(AndroidJUnit4::class)
class DiagnosticRowLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ThemeTestActivity>()

    @Test fun longValueKeepsLabelReadable() {
        compose.setContent {
            // Phone-width settings column with the real WP-04 device build string.
            Box(Modifier.width(360.dp)) {
                DiagnosticRow("Android build", "16 (API 36) · BP2A.250605.031.A3.S918NKSS7EZCI")
            }
        }
        val label = compose.onNodeWithText("Android build").getUnclippedBoundsInRoot()
        val value = compose.onNodeWithText("16 (API 36) · BP2A.250605.031.A3.S918NKSS7EZCI").getUnclippedBoundsInRoot()
        // Before the fix the label got a few dp and wrapped one character per line.
        assertTrue("label width ${label.width}", label.width >= 120.dp)
        assertTrue("label taller than value: ${label.height} > ${value.height}", label.height <= value.height)
    }
}
