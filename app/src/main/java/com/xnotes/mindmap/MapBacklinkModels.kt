package com.xnotes.mindmap

import android.net.Uri
import com.xnotes.ui.NativeBacklinkContext

internal enum class BacklinkGroup { CURRENT_PAGE,OTHER_PAGE,WHOLE_FILE }
internal enum class BacklinkOrigin { STORED,RECOVERY,LIVE }
internal enum class BacklinkProblem {
    UNAVAILABLE,CORRUPT,RECOVERY_CONFLICT,DUPLICATE,IDENTITY,LOADING,LIMIT,CHANGED,BUSY,SAVE_FAILED,TRASHED,UNKNOWN_SCOPE,CANCELLED
}

/** A location is evidence about a file, never an instruction to resolve an arbitrary matching ID. */
internal class BacklinkLocation(val uri:Uri,aliases:Set<String>,val key:String,val graphId:String,val title:String) {
    val aliases:Set<String> = aliases.toSet()
}
internal data class BacklinkPreview(val assetKey:String,val depictedTarget:MapSourceTarget,val renderedVersion:String,val generatedAt:Long)

/** Lightweight projection. Whole graph documents, PNGs and native source models stay out of rows. */
internal data class MapBacklinkRow internal constructor(
    val location:BacklinkLocation,val nodeId:String,val sourceId:String,val target:MapSourceTarget,
    val nodeTitle:String,val kind:NodeKind,val sourceFileName:String,val sourceNote:String,
    val lastKnownPage:Int?,val status:SourceStatus,val preview:BacklinkPreview?,
    val origin:BacklinkOrigin,val conflict:Boolean=false,val blocked:BacklinkProblem?=null,
    internal val membership:MapBacklinkMembership?=null,
) {
    // Length prefixes keep user-editable IDs containing separators unambiguous in lazy-list keys.
    val key:String get()=listOf(location.key,location.graphId,nodeId,sourceId).joinToString("") {"${it.length}:$it"}
    fun group(pageId:String?)=when {
        target.scope==SourceScope.FILE->BacklinkGroup.WHOLE_FILE
        pageId!=null && target.pageId==pageId->BacklinkGroup.CURRENT_PAGE
        else->BacklinkGroup.OTHER_PAGE
    }
}
internal data class BacklinkIssue(val problem:BacklinkProblem,val displayName:String?=null)
internal data class BacklinkReport(val rows:List<MapBacklinkRow>,val issues:List<BacklinkIssue>,val checkedMaps:Int,
    val complete:Boolean,val omittedIssues:Int=0,val excludedMaps:Int=0)

/** Source-panel request capability. Closing or changing that panel retires an unfinished handoff. */
internal class MapBacklinkOpenRequest private constructor(
    val context:NativeBacklinkContext,val row:MapBacklinkRow,private val valid:()->Boolean,
    internal val review:MapBacklinkRemovalTicket?=null,
) {
    fun current()=row.target.documentId==context.documentId && context.current() && valid()
    companion object {
        internal fun capture(context:NativeBacklinkContext,row:MapBacklinkRow,valid:()->Boolean,
            review:MapBacklinkRemovalTicket?=null)=MapBacklinkOpenRequest(context,row,valid,review)
    }
}

/** Only this hook wrapper is public, so the existing public SidePanel keeps its signature usable. */
class BacklinkHostHooks internal constructor(
    internal val open:(MapBacklinkOpenRequest)->Boolean,
    internal val busy:()->Boolean={false},
    internal val cancel:(NativeBacklinkContext)->Unit={},
    internal val canCancel:(NativeBacklinkContext)->Boolean={false},
    internal val problem:(NativeBacklinkContext)->BacklinkProblem?={null},
    internal val openNetwork:((LocalNetworkLaunchRequest)->Boolean)?=null,
)

internal sealed interface BacklinkRemovalState {
    val row:MapBacklinkRow
    val token:Any
    val canReview:Boolean get()=false
    data class Preparing(override val row:MapBacklinkRow,override val token:Any):BacklinkRemovalState
    data class Confirm(override val row:MapBacklinkRow,override val token:Any):BacklinkRemovalState
    data class Saving(override val row:MapBacklinkRow,override val token:Any):BacklinkRemovalState
    data class Removed(override val row:MapBacklinkRow,override val token:Any,val newerChanges:Boolean,
        override val canReview:Boolean=true):BacklinkRemovalState
    /** Saving after an undo is valid, but it is no longer confirmation of the original removal. */
    data class SavedChanged(override val row:MapBacklinkRow,override val token:Any,val newerChanges:Boolean):BacklinkRemovalState
    data class Failed(override val row:MapBacklinkRow,override val token:Any,val problem:BacklinkProblem,
        override val canReview:Boolean=false,val canRetrySave:Boolean=false):BacklinkRemovalState
}

/** Global budgets apply across individually valid graphs, not just to one JSON or image. */
internal data class BacklinkBudget(
    val maxDirectories:Int=4096,val maxMaps:Int=4096,val maxBytes:Long=256L*1024*1024,
    val maxRows:Int=5000,val maxIssues:Int=128,
) {
    init {require(maxDirectories>0 && maxMaps>0 && maxBytes>0 && maxRows>0 && maxIssues>0)}
    companion object {
        const val LABEL_CHARS=256
        const val NOTE_CHARS=512
        const val PREVIEW_ITEMS=8
        const val PREVIEW_BYTES=2*1024*1024
        const val PREVIEW_PIXELS=8*1024*1024
    }
}
