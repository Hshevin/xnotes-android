package com.xnotes.ui

import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
import java.util.UUID

/**
 * The single semantic contract for recovery descriptions. [LastPresentationCodec] applies it after strict
 * JSON parsing and before encoding; in-memory restoration applies it before reading any private cache or
 * allocating a pane. Accepting a description never grants access to an original source.
 */
internal object LastPresentationContract {
    const val MIN_RATIO=.18f
    const val MAX_RATIO=.82f

    fun accepts(value:LastPresentation):Boolean = try {requireValid(value);true} catch(_:IllegalArgumentException) {false}

    fun requireValid(value:LastPresentation) {
        when(value) {
            LastPresentation.Home->Unit
            is LastPresentation.Native->validateNative(value)
        }
    }

    fun text(value:String) {require(value.length<=LastPresentationCodec.MAX_BYTES && Charsets.UTF_8.newEncoder().canEncode(value))}
    fun validateHint(value:NativeRestoreHint,kind:DocumentKind) {
        require(value.kind==kind && UUID.fromString(value.documentId).toString()==value.documentId)
        value.path?.let {text(it);require(it.isNotBlank())}
    }
    private fun validateRatio(ratio:Float) {require(ratio.isFinite() && ratio in MIN_RATIO..MAX_RATIO)}
    private fun validateView(position:Editor.ReadingPosition) {
        require(position.zoom.isFinite() && position.zoom in .05..20.0 && position.x.isFinite() && position.y.isFinite())
        val view=position.overrides
        view.contrast?.let {require(it in 0..200)};view.invert?.let {require(it in 0..100)}
        view.brightness?.let {require(it in 0..200)};view.sepia?.let {require(it in 0..200)}
        view.rotation?.let {require(it in listOf(0,90,180,270))}
        for(color in listOfNotNull(view.multiply,view.screen))
            require(color.r in 0..255 && color.g in 0..255 && color.b in 0..255 && color.a==255)
    }
    fun validateNative(value:LastPresentation.Native) {
        require(value.slots.size in 1..2 && value.slots.map {it.pane}.distinct().size==value.slots.size)
        require(value.layout.focusedPane in value.slots.map {it.pane});validateRatio(value.layout.ratio)
        require(value.slots.any {it !is ColdNativeSlot.Home})
        val notes=value.slots.filterIsInstance<ColdNativeSlot.Note>()
        require(notes.map {it.hint.documentId}.distinct().size==notes.size)
        for(slot in value.slots) when(slot) {
            is ColdNativeSlot.Note->{validateHint(slot.hint,DocumentKind.NOTE);require(!slot.reopenFromFile || slot.hint.path!=null);slot.view?.position?.let(::validateView)}
            is ColdNativeSlot.Canvas->{
                require(value.slots.size==1);validateHint(slot.hint,DocumentKind.CANVAS)
                require(!slot.reopenFromFile || slot.hint.path!=null)
                slot.view?.let {require(it.centerX.isFinite() && it.centerY.isFinite() && it.zoom.isFinite() && it.zoom>0)}
            }
            is ColdNativeSlot.Shared->{
                require(value.slots.size==2 && slot.ownerPane!=slot.pane && notes.any {it.pane==slot.ownerPane})
                slot.view?.position?.let(::validateView)
            }
            is ColdNativeSlot.Home->Unit
        }
    }
}
