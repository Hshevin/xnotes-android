package com.xnotes.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.Space
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that [AiSegmented] measures cleanly inside intrinsic-width containers (such as Material 3
 * [DropdownMenu], which crashes on SubcomposeLayout/BoxWithConstraints) and handles equal-width,
 * proportional-fill, and horizontal-scroll overflow cases without clipping labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiSegmentedLayoutTest {
    @Test fun computeSegmentedWidthsSplitsEquallyWhenAllFitProportionallyWhenTightAndScrollsOnOverflow() {
        // 1. All 3 segments fit equally.
        assertArrayEquals(intArrayOf(99, 99, 98), computeSegmentedWidths(intArrayOf(48, 48, 48), viewportWidth = 300, gapPx = 2))
        // 2. <=5 segments with one long custom label: does not clip to equal width; fills viewport proportionally.
        assertArrayEquals(intArrayOf(58, 58, 180), computeSegmentedWidths(intArrayOf(48, 48, 170), viewportWidth = 300, gapPx = 2))
        // 3. <=5 segments whose combined natural widths overflow narrow viewport: keeps full natural widths for horizontal scroll.
        assertArrayEquals(intArrayOf(140, 160, 140), computeSegmentedWidths(intArrayOf(140, 160, 140), viewportWidth = 300, gapPx = 2))
        // 4. 7 graded levels on a tablet bar fit equally.
        assertArrayEquals(intArrayOf(78, 78, 78, 78, 78, 78, 78), computeSegmentedWidths(IntArray(7) { 52 }, viewportWidth = 558, gapPx = 2))
    }

    @Test fun segmentedBarMeasuresInsideIntrinsicWidthColumnAndMaterialDropdownMenuWithoutThrowing() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val view = ComposeView(activity).apply {
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalPalette provides Palette.DEFAULT) {
                            Column(Modifier.width(IntrinsicSize.Max)) {
                                AiSegmented(
                                    options = listOf("" to "默认", "off" to "关闭", "on" to "开启"),
                                    selected = "on",
                                    modifier = Modifier.fillMaxWidth(),
                                ) {}
                                AiSegmented(
                                    options = listOf(
                                        "" to "默认", "off" to "关闭", "minimal" to "极低",
                                        "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "超高",
                                    ),
                                    selected = "xhigh",
                                    modifier = Modifier.fillMaxWidth(),
                                ) {}
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = {},
                                    modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
                                ) {
                                    DropdownMenuItem(text = { Text("gpt-6.2") }, onClick = {})
                                    AiSegmented(
                                        options = listOf(
                                            "" to "Default", "off" to "Off", "budget-16k" to "budget-16k-thinking",
                                        ),
                                        selected = "budget-16k",
                                        modifier = Modifier.padding(horizontal = Space.s12).fillMaxWidth(),
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }
            activity.setContentView(view)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("ComposeView should measure with positive width", view.measuredWidth > 0)
            assertTrue("ComposeView should measure with positive height", view.measuredHeight > 0)
        } finally {
            controller.destroy()
        }
    }
}
