package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.util.UUID

/** Exact reference target, independent of labels, current availability and user remarks. */
data class MapSourceTarget(val documentId:String,val scope:SourceScope,val pageId:String?=null,val region:Rect?=null) {
    fun validate() {
        require(UUID.fromString(documentId).toString()==documentId)
        require(pageId==null || UUID.fromString(pageId).toString()==pageId)
        region?.let {r->require(listOf(r.x,r.y,r.w,r.h).all {it.isFinite() && kotlin.math.abs(it)<=MindMapDocument.MAX_COORD} && r.w>0 && r.h>0)}
        when(scope) {
            SourceScope.FILE->require(pageId==null && region==null)
            SourceScope.PAGE->require(pageId!=null)
            SourceScope.CANVAS_REGION->require(pageId==null && region!=null)
        }
    }
    companion object {fun from(source:MapSource)=MapSourceTarget(source.documentId,source.scope,source.pageId,source.region)}
}

/** What the retained pixels actually depict, even if the current reference is later replaced. */
data class MapLastPreview(val assetKey:String,val target:MapSourceTarget,val renderedVersion:String,val generatedAt:Long) {
    fun validate() {
        require(assetKey.matches(Regex("[0-9a-f]{64}")) && renderedVersion.isNotBlank() && renderedVersion.length<=512 && generatedAt>=0)
        target.validate()
    }
}

/** Cached pixels can remain useful after an empty refresh or failed/newer observation. */
internal fun historicalPreview(target:MapSourceTarget,status:SourceStatus,depicted:MapSourceTarget,rendered:String,
    observed:String?,accepted:String?=null,raw:String?=null)=depicted!=target || status!=SourceStatus.CURRENT ||
    observed?.let {it!=rendered}==true || accepted?.let {it!=rendered}==true ||
    rendered.startsWith("native-sha256:") && raw?.let {rendered!="native-sha256:$it"}==true
internal val MapSource.hasHistoricalPreview:Boolean get()=lastPreview?.let {
    historicalPreview(target,status,it.target,it.renderedVersion,observedVersion,acceptedPreviewVersion,sourceVersion)
} ?: false
