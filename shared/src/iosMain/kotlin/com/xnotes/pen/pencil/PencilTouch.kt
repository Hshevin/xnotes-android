@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.pen.pencil

import com.xnotes.core.geometry.Pt
import kotlinx.cinterop.useContents
import platform.UIKit.UITouch
import platform.UIKit.UITouchPropertyForce
import platform.UIKit.UIView

fun UITouch.toPencilSample(view: UIView, toContent: (Pt) -> Pt): PencilSample {
    val position = preciseLocationInView(view).useContents { toContent(Pt(x, y)) }
    return PencilSample(
        position.x, position.y, timestamp, force, maximumPossibleForce, estimationUpdateIndex?.longLongValue,
        (estimatedPropertiesExpectingUpdates and UITouchPropertyForce) != 0L, rollAngle,
    )
}
