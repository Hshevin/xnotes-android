package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.ui.MixedSplitRequest
import com.xnotes.ui.SplitSourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Location hints are resolved once. Actual file reads still prove both roles and identities. */
internal class SavedMixedSplitResolver(context:Context) {
    private val registry=NotebookStorage.get(context.applicationContext).registry
    private val originals=OriginalPdfCatalog(registry)
    suspend fun resolve(request:MixedSplitRequest):MixedSplitRequest=withContext(Dispatchers.IO) {
        val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
        val ids=setOf(request.map.documentId,request.native.documentId)
        if(ids.size!=2) throw MapPairChanged()
        val tickets=ids.map {registry.identityObservation(it)}
        val domain=originals.identityDomains(ids,check)
        if(!domain.supported || domain.unverifiable || domain.reservedOriginalIds.any {it in ids}) throw MapPairChanged()
        fun location(source:SplitSourceRef,kind:String):SplitSourceRef {
            check()
            if(registry.kindForId(source.documentId)?.let {it!=kind}==true ||
                kind=="xmap" && registry.expectationForId(source.documentId).requiresPages) throw MapPairChanged()
            // resolve's duplicate error must never fall back to the old URI hint.
            return source.copy(uri=registry.resolve(source.documentId) ?: source.uri)
        }
        val resolved=request.copy(map=location(request.map,"xmap"),native=location(request.native,"xnote"))
        if(registry.locationKey(resolved.map.uri)==registry.locationKey(resolved.native.uri) ||
            MapSaves.key(Uri.parse(resolved.map.uri))==MapSaves.key(Uri.parse(resolved.native.uri))) throw MapPairChanged()
        check()
        if(tickets.any {!it.current()} || !domain.current()) throw MapPairChanged()
        resolved
    }
}
