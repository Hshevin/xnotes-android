package com.xnotes.ui

import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
import com.xnotes.mindmap.MapPresentationCamera
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
            is LastPresentation.Mixed->validateMixed(value.value)
            is LastPresentation.MapOnly->validateMapOnly(value)
            is LastPresentation.Native->validateNative(value)
        }
    }

    fun text(value:String) {require(value.length<=LastPresentationCodec.MAX_BYTES && Charsets.UTF_8.newEncoder().canEncode(value))}
    fun validateMap(value:SplitSourceRef) {
        require(UUID.fromString(value.documentId).toString()==value.documentId)
        text(value.uri);require(value.uri.isNotBlank());value.title?.let(::text)
    }
    fun validateHint(value:NativeRestoreHint,kind:DocumentKind) {
        require(value.kind==kind && UUID.fromString(value.documentId).toString()==value.documentId)
        value.path?.let {text(it);require(it.isNotBlank())}
    }
    fun validateCamera(value:MapPresentationCamera) {
        require(value.viewport.zoom.isFinite() && value.viewport.zoom in .05f..4f && value.viewport.panX.isFinite() && value.viewport.panY.isFinite())
        require(value.width>0 && value.height>0 && value.density.isFinite() && value.density>0)
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
    fun validateMixed(data:ColdMixedPresentation) {
        validateMap(data.map);validateHint(data.nativeHint,DocumentKind.NOTE)
        require(data.map.documentId!=data.nativeHint.documentId && !data.layout.nativeHome);validateRatio(data.layout.ratio)
        require(data.nativeView==null || data.nativePosition==null)
        data.mapCamera?.let(::validateCamera);data.nativePosition?.let(::validateView);data.nativeView?.position?.let(::validateView)
    }
    fun validateMapOnly(value:LastPresentation.MapOnly) {
        validateMap(value.map);value.camera?.let(::validateCamera)
        value.homeLayout?.let {require(it.nativeHome);validateRatio(it.ratio)}
    }
    fun validateNative(value:LastPresentation.Native) {
        require(value.slots.size in 1..2 && value.slots.map {it.pane}.distinct().size==value.slots.size)
        require(value.layout.focusedPane in value.slots.map {it.pane});validateRatio(value.layout.ratio)
        require(value.slots.any {it !is ColdNativeSlot.Home})
        val notes=value.slots.filterIsInstance<ColdNativeSlot.Note>()
        require(notes.map {it.hint.documentId}.distinct().size==notes.size)
        for(slot in value.slots) when(slot) {
            is ColdNativeSlot.Note->{validateHint(slot.hint,DocumentKind.NOTE);slot.view?.position?.let(::validateView)}
            is ColdNativeSlot.Canvas->{
                require(value.slots.size==1);validateHint(slot.hint,DocumentKind.CANVAS)
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
