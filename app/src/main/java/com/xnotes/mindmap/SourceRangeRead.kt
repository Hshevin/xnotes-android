package com.xnotes.mindmap

import android.graphics.Bitmap
import android.net.Uri
import com.xnotes.core.geometry.Rect
import com.xnotes.storage.OriginalPdfPageInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal enum class SourceRangeKind {NOTE,CANVAS,MAP,PDF_ORIGINAL}
internal enum class SourceRangeSpace {PAGE_LOCAL,CANVAS_WORLD,PDF_DISPLAY,MAP_WORLD}
internal enum class SourceRangeInitialIssue {PAGE_DELETED,INVALID_REGION}
internal enum class SourceRangeScope {WHOLE_FILE,WHOLE_PAGE,REGION}
internal data class SourceRangePage(val id:String,val number:Int)

/** One actual holder/page/transform. User input cannot retarget a frame to a different read. */
internal class SourceRangeFrame private constructor(internal val owner:Any,val pageId:String?,val viewTurns:Int,
    val bounds:Rect,val initialRegion:Rect?,val space:SourceRangeSpace,val regionAllowed:Boolean,
    val allowsOutsideRegions:Boolean,internal val index:Int,internal val pdfInfo:OriginalPdfPageInfo?) {
    companion object {internal fun issue(owner:Any,pageId:String?,viewTurns:Int,bounds:Rect,initialRegion:Rect?,
        space:SourceRangeSpace,regionAllowed:Boolean,allowsOutsideRegions:Boolean,index:Int,pdfInfo:OriginalPdfPageInfo?=null)=
        SourceRangeFrame(owner,pageId,viewTurns,bounds,initialRegion,space,regionAllowed,allowsOutsideRegions,index,pdfInfo)}
}

/** Unpublished images may be recycled. Once published, renderer/Compose/GC owns their lifetime. */
internal class OwnedVisualFrame internal constructor(val bitmap:Bitmap,val frame:Rect):AutoCloseable {
    private val gate=Any()
    private var published=false
    private var closed=false
    fun publish()=synchronized(gate) {check(!closed);published=true}
    override fun close()=synchronized(gate) {
        if(!closed) {closed=true;if(!published && !bitmap.isRecycled) bitmap.recycle()}
    }
}

/** Issued only by an actual read after final IO verification; never a native source-write permit. */
internal class PreparedSourceTargetChoice private constructor(val target:MapSourceTarget,val displayName:String,
    val rawVersion:String,val lastKnownPage:Int?,private val valid:()->Boolean) {
    fun current()=valid()
    companion object {internal fun issue(target:MapSourceTarget,name:String,raw:String,page:Int?,valid:()->Boolean)=
        PreparedSourceTargetChoice(target,name,raw,page,valid)}
}

internal abstract class SourceRangeRead {
    abstract val kind:SourceRangeKind
    abstract val documentId:String
    abstract val displayName:String
    abstract val rawVersion:String
    abstract val pages:List<SourceRangePage>
    abstract val needsPageActivation:Boolean
    abstract val nativeOffer:SourceTargetOffer.Native?
    abstract val initialPageId:String?
    abstract val initialTurns:Int
    open val initialIssue:SourceRangeInitialIssue? get()=null
    abstract fun current():Boolean
    /** IO only. For an unactivated note, null previews its actual first page without creating IDs. */
    abstract fun frame(pageId:String?=initialPageId,viewTurns:Int=initialTurns):SourceRangeFrame
    abstract fun render(frame:SourceRangeFrame,viewport:Rect,maxSide:Int=2048,check:()->Unit={}):OwnedVisualFrame
    /** Includes the final actual-source re-read; Main may then adopt while this read stays open. */
    abstract fun prepareChoice(frame:SourceRangeFrame,scope:SourceRangeScope,region:Rect?=null,
        check:()->Unit={}):PreparedSourceTargetChoice
    abstract fun closeAndAwait()
}

internal sealed class SourceTargetOffer(val uri:Uri,val displayName:String,private val valid:()->Boolean) {
    private val closed=AtomicBoolean()
    internal fun active()=!closed.get()
    fun current()=active() && valid()
    open fun closeAndAwait() {closed.set(true)}
    internal class Native private constructor(val offer:MapSourceOffer,valid:()->Boolean):
        SourceTargetOffer(offer.uri,offer.name,valid) {
        companion object {internal fun issue(offer:MapSourceOffer,valid:()->Boolean)=Native(offer,valid)}
    }
    internal class Map private constructor(uri:Uri,name:String,val documentId:String,val rawVersion:String,
        internal val document:MindMapDocument,valid:()->Boolean):SourceTargetOffer(uri,name,valid) {
        companion object {internal fun issue(uri:Uri,name:String,id:String,raw:String,document:MindMapDocument,valid:()->Boolean)=
            Map(uri,name,id,raw,document,valid)}
    }
    internal class RawPdf private constructor(uri:Uri,name:String,valid:()->Boolean):SourceTargetOffer(uri,name,valid) {
        companion object {internal fun issue(uri:Uri,name:String,valid:()->Boolean)=RawPdf(uri,name,valid)}
    }
}

internal enum class OriginalPickerState {ROW_AVAILABLE,ROW_DELETED,CORRUPT,UNSUPPORTED}
internal class OriginalPickerCursor internal constructor(internal val databaseKey:String,internal val rowId:Long,
    internal val valid:()->Boolean) {fun current()=valid()}
internal class OriginalPickerEntry internal constructor(val documentId:String?,val title:String?,val deleted:Boolean?,
    val state:OriginalPickerState,internal val generation:Long?,internal val sha256:String?,internal val valid:()->Boolean) {
    fun current()=valid()
}
internal class OriginalPickerPage internal constructor(val entries:List<OriginalPickerEntry>,val next:OriginalPickerCursor?,
    val supported:Boolean,private val valid:()->Boolean) {fun current()=valid()}

internal sealed interface SourcePdfRetention {
    data class Retained(val read:SourceRangeRead):SourcePdfRetention
    data class RestoreRequired(val offer:SourcePdfRestoreOffer):SourcePdfRetention
}
internal class SourcePdfRestoreOffer internal constructor(internal val file:File,val displayName:String,
    internal val expected:com.xnotes.storage.OriginalPdfCatalog.Observation) {
    private val closed=AtomicBoolean()
    fun current()=!closed.get() && expected.current()
    fun closeAndAwait() {if(closed.compareAndSet(false,true)) file.delete()}
}
