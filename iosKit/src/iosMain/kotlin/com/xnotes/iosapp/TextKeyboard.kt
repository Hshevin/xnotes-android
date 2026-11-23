@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.text.*
import com.xnotes.edit.FlowTextInput
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*
import kotlin.native.ref.WeakReference

private class Position(val index: Int, val owner: TextTarget?) : UITextPosition()
private class Span(val first: Position, val last: Position) : UITextRange() {
    override fun start() = first
    override fun end() = last
    override fun isEmpty() = first.index == last.index
}
private class SelectionBox(val box: Rect, val first: Boolean, val last: Boolean) : UITextSelectionRect() {
    override fun rect() = box.cg()
    override fun writingDirection() = NSWritingDirectionLeftToRight
    override fun containsStart() = first
    override fun containsEnd() = last
    override fun isVertical() = false
}

internal class TextKeyboard(private val surface: TextSurface) : UIView(frame = CGRectZero.readValue()), UITextInputProtocol {
    private val input get() = surface.target?.input
    private val count get() = input?.utf16Length() ?: 0
    private var observer: WeakReference<UITextInputDelegateProtocol>? = null
    private val words by lazy { UITextInputStringTokenizer(this) }
    private val menu = UIEditMenuInteraction(null)
    private var markStyle: Map<Any?, *>? = null
    private var touch: UITouch? = null
    private var down = Pt.ZERO
    private var selecting = false
    init {
        opaque = false; backgroundColor = UIColor.clearColor; multipleTouchEnabled = true
        addInteraction(menu)
        accessibilityLabel = text("text_editor")
    }
    override fun inputDelegate() = observer?.get()
    override fun setInputDelegate(inputDelegate: UITextInputDelegateProtocol?) { observer = inputDelegate?.let(::WeakReference) }
    override fun tokenizer() = words
    override fun canBecomeFirstResponder() = !hidden && input != null
    override fun hasText() = count > 0
    override fun markedTextStyle() = markStyle
    override fun setMarkedTextStyle(markedTextStyle: Map<Any?, *>?) { markStyle = markedTextStyle }

    private fun position(index: Int) = Position(index.coerceIn(0, count), surface.target)
    private fun index(position: UITextPosition) = (position as? Position)?.takeIf { it.owner === surface.target }?.index?.coerceIn(0, count)
    private fun span(start: Int, end: Int) = Span(position(minOf(start, end)), position(maxOf(start, end)))
    private fun flowRange(range: UITextRange): FlowRange? {
        val current = input ?: return null
        return FlowRange(current.positionAt(index(range.start) ?: return null), current.positionAt(index(range.end) ?: return null))
    }
    private fun nativeRange(range: FlowRange?): UITextRange? {
        val current = input ?: return null
        return range?.let { span(current.offsetOf(it.start), current.offsetOf(it.end)) }
    }
    override fun beginningOfDocument(): UITextPosition = position(0)
    override fun endOfDocument(): UITextPosition = position(count)
    override fun selectedTextRange() = nativeRange(input?.selection)
    override fun markedTextRange() = nativeRange(input?.marked)
    override fun setSelectedTextRange(selectedTextRange: UITextRange?) {
        val range = selectedTextRange?.let(::flowRange) ?: return
        input?.selection = range; input?.typingStyle = null; surface.selectionChanged()
    }
    override fun textInRange(range: UITextRange): String? {
        return input?.textIn(index(range.start) ?: return null, index(range.end) ?: return null)
    }
    private inline fun edit(op: FlowTextInput.() -> Unit) {
        val current = surface.target?.takeIf { !hidden && it.writable() } ?: return
        current.input.op(); surface.changed()
    }
    override fun insertText(text: String) = edit { insertText(text) }
    override fun deleteBackward() = edit { deleteBackward() }
    override fun replaceRange(range: UITextRange, withText: String) {
        val replace = flowRange(range) ?: return
        edit {
            if (marked != null && replace != marked) unmarkText()
            selection = replace; insertText(withText)
        }
    }
    override fun setMarkedText(markedText: String?, selectedRange: CValue<NSRange>) = edit {
        val value = markedText.orEmpty()
        val range = selectedRange.useContents {
            val start = location.coerceAtMost(value.length.toULong()).toInt()
            start..(start + length.coerceAtMost((value.length - start).toULong()).toInt())
        }
        setMarkedText(value, range)
    }
    override fun unmarkText() = edit { unmarkText() }
    fun externalChange(text: Boolean, operation: () -> Unit) {
        val delegate = inputDelegate()
        if (text) delegate?.textWillChange(this)
        delegate?.selectionWillChange(this)
        operation()
        delegate?.selectionDidChange(this)
        if (text) delegate?.textDidChange(this)
    }

    override fun textRangeFromPosition(fromPosition: UITextPosition, toPosition: UITextPosition): UITextRange? =
        index(fromPosition)?.let { start -> index(toPosition)?.let { end -> span(start, end) } }
    override fun positionFromPosition(position: UITextPosition, offset: Long): UITextPosition? = index(position)?.let {
        if (offset < -it.toLong() || offset > (count - it).toLong()) null else position(it + offset.toInt())
    }
    override fun positionFromPosition(position: UITextPosition, inDirection: Long, offset: Long): UITextPosition? {
        val current = surface.target ?: return null
        val start = index(position) ?: return null
        if (inDirection == UITextLayoutDirectionUp || inDirection == UITextLayoutDirectionDown) {
            val local = current.caret(current.input.positionAt(start))?.let(surface::screen) ?: return null
            val sign = if (inDirection == UITextLayoutDirectionUp) -1 else 1
            val next = surface.hit(Pt(local.x, local.centerY + sign * offset * local.h)) ?: return null
            return position(current.input.offsetOf(next))
        }
        return positionFromPosition(position, if (inDirection == UITextLayoutDirectionLeft) -offset else offset)
    }
    override fun comparePosition(position: UITextPosition, toPosition: UITextPosition) =
        (index(position) ?: 0).compareTo(index(toPosition) ?: 0).toLong()
    override fun offsetFromPosition(from: UITextPosition, toPosition: UITextPosition) = ((index(toPosition) ?: 0) - (index(from) ?: 0)).toLong()
    private fun backwards(direction: Long) = direction == UITextLayoutDirectionLeft || direction == UITextLayoutDirectionUp
    override fun positionWithinRange(range: UITextRange, farthestInDirection: Long) = if (backwards(farthestInDirection)) range.start else range.end
    override fun characterRangeByExtendingPosition(position: UITextPosition, inDirection: Long): UITextRange? {
        val at = index(position) ?: return null
        val text = input?.flow?.plainText() ?: return null
        val inside = if (backwards(inDirection)) at - 1 else at
        if (inside !in text.indices) return span(at, at)
        return (text as NSString).rangeOfComposedCharacterSequenceAtIndex(inside.toULong()).useContents {
            span(location.toInt(), (location + length).toInt())
        }
    }
    override fun baseWritingDirectionForPosition(position: UITextPosition, inDirection: Long) = NSWritingDirectionLeftToRight
    override fun setBaseWritingDirection(writingDirection: Long, forRange: UITextRange) {}
    private fun boxes(range: UITextRange): List<Rect> = flowRange(range)?.let { surface.target?.rects(it) }.orEmpty().map(surface::screen)
    override fun caretRectForPosition(position: UITextPosition): CValue<CGRect> {
        val current = surface.target ?: return CGRectZero.readValue()
        return index(position)?.let { current.caret(current.input.positionAt(it)) }?.let(surface::screen)?.cg() ?: CGRectZero.readValue()
    }
    override fun firstRectForRange(range: UITextRange) = boxes(range).firstOrNull()?.cg() ?: caretRectForPosition(range.start)
    override fun selectionRectsForRange(range: UITextRange): List<*> = boxes(range).let { rects ->
        rects.mapIndexed { index, rect -> SelectionBox(rect, index == 0, index == rects.lastIndex) }
    }
    override fun closestPositionToPoint(point: CValue<CGPoint>): UITextPosition? = point.useContents { surface.hit(Pt(x, y)) }?.let { position(input!!.offsetOf(it)) }
    override fun closestPositionToPoint(point: CValue<CGPoint>, withinRange: UITextRange): UITextPosition? {
        val value = closestPositionToPoint(point)?.let(::index) ?: return null
        return position(value.coerceIn(index(withinRange.start) ?: return null, index(withinRange.end) ?: return null))
    }
    override fun characterRangeAtPoint(point: CValue<CGPoint>) = closestPositionToPoint(point)?.let { characterRangeByExtendingPosition(it, UITextLayoutDirectionRight) }

    override fun copy(sender: Any?) { selectedTextRange()?.takeUnless { it.empty }?.let { UIPasteboard.generalPasteboard.string = textInRange(it) } }
    override fun cut(sender: Any?) { if (input?.selection?.collapsed == false) { copy(sender); externalChange(true) { deleteBackward() } } }
    override fun paste(sender: Any?) { UIPasteboard.generalPasteboard.string?.let { externalChange(true) { insertText(it) } } }
    override fun selectAll(sender: Any?) { externalChange(false) { surface.settle(); setSelectedTextRange(span(0, count)) } }
    override fun canPerformAction(action: COpaquePointer?, withSender: Any?): Boolean = when (action?.let(::NSStringFromSelector)) {
        "copy:", "cut:" -> input?.selection?.collapsed == false
        "paste:" -> input != null && UIPasteboard.generalPasteboard.hasStrings
        "selectAll:" -> hasText()
        else -> super.canPerformAction(action, withSender)
    }
    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        val key = presses.filterIsInstance<UIPress>().singleOrNull()?.key
        if (key != null && input != null && input?.marked == null) {
            val shift = key.modifierFlags and UIKeyModifierShift != 0L
            if (key.modifierFlags and UIKeyModifierCommand != 0L) when (key.charactersIgnoringModifiers.lowercase()) {
                "a" -> { selectAll(this); return }
                "c" -> { copy(this); return }
                "x" -> { cut(this); return }
                "v" -> { paste(this); return }
                "z" -> { surface.editor.replay(!shift); return }
            }
            if (key.modifierFlags and (UIKeyModifierAlternate or UIKeyModifierCommand or UIKeyModifierControl) == 0L) {
                val direction = when (key.charactersIgnoringModifiers) {
                    UIKeyInputLeftArrow -> UITextLayoutDirectionLeft
                    UIKeyInputRightArrow -> UITextLayoutDirectionRight
                    UIKeyInputUpArrow -> UITextLayoutDirectionUp
                    UIKeyInputDownArrow -> UITextLayoutDirectionDown
                    else -> null
                }
                if (direction != null) { moveCaret(direction, shift); return }
            }
        }
        super.pressesBegan(presses, withEvent)
    }

    private fun moveCaret(direction: Long, extend: Boolean) {
        val current = input ?: return
        val previous = current.selection
        val pos = position(current.offsetOf(previous.end))
        val next = when {
            !extend && !previous.collapsed -> if (backwards(direction)) previous.normalized().start else previous.normalized().end
            direction == UITextLayoutDirectionLeft || direction == UITextLayoutDirectionRight -> {
                val char = characterRangeByExtendingPosition(pos, direction) ?: return
                current.positionAt(index(if (backwards(direction)) char.start else char.end) ?: return)
            }
            else -> current.positionAt(positionFromPosition(pos, direction, 1L)?.let(::index) ?: return)
        }
        externalChange(false) {
            current.selection = if (extend) FlowRange(previous.start, next) else FlowRange.caret(next)
            current.typingStyle = null; surface.selectionChanged()
        }
    }

    private fun UITouch.point() = locationInView(this@TextKeyboard).useContents { Pt(x, y) }
    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        menu.dismissMenu()
        if (touch != null || touches.size != 1) { touch = null; return }
        touch = touches.singleOrNull() as? UITouch
        down = touch?.point() ?: return; selecting = false
    }
    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        val current = touch?.takeIf { it in touches } ?: return
        val point = current.point()
        if (!selecting && down.distanceTo(point) >= 3) selecting = surface.start(down, false)
        if (selecting) surface.drag(point)
    }
    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        val current = touch?.takeIf { it in touches } ?: return
        touch = null
        val point = current.point()
        val repeated = isFirstResponder() && surface.hit(point) == input?.selection?.end
        if (selecting) surface.drag(point) else if (!surface.start(point, true)) return
        if (current.tapCount >= 2u) input?.let { externalChange(false) { it.selection = wordRangeAt(it.flow, it.selection.end); surface.selectionChanged() } }
        if (selecting || repeated || current.tapCount >= 2u)
            menu.presentEditMenuWithConfiguration(UIEditMenuConfiguration.configurationWithIdentifier(null, CGPointMake(point.x, point.y)))
    }
    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) { touch = null; selecting = false }
    override fun drawRect(rect: CValue<CGRect>) { UIGraphicsGetCurrentContext()?.let(surface::draw) }
}
