package com.xnotes.mindmap

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkOverviewPresentationTest {
    private val s=UUID.randomUUID().toString();private val t=UUID.randomUUID().toString();private val g=UUID.randomUUID().toString()
    private val location=BacklinkLocation(Uri.parse("content://overview/document/g"),emptySet(),"canonical-g",g,"G.xmap")
    private val key=NetworkConceptKey(location.key,g,"concept")
    private val owner=Any()
    private val labels=LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs","No files in this view")
    private val members=listOf(s,t).mapIndexed {i,id->LocalNetworkEvidence(MapBacklinkRow(location,"concept","source-$i",
        MapSourceTarget(id,SourceScope.FILE),"Concept",NodeKind.TEXT,"$i.xnote","",null,SourceStatus.UNVERIFIED,null,BacklinkOrigin.STORED),owner,0)}
    private fun report(mode:NetworkBrowseMode,files:List<String> = listOf(s,g,t),concept:Boolean=true,centres:Set<String> = emptySet())=
        LocalFileNetworkReport(s,files.map {LocalNetworkFile(it,listOf(it))},if(concept)
            listOf(LocalNetworkConcept(key,location,"Concept",NodeKind.TEXT,members)) else emptyList(),emptyList(),1,true,
            browseMode=mode,centreFiles=centres)
    private fun point(scene:LocalNetworkScene,key:NetworkSceneNodeKey)=scene.document.node(scene.nodes.entries.single {it.value==key}.key).bounds

    @Test fun overviewAndCollectionUseOnlyRealFilesAndNeverMoveAnExistingFileToMakeItTheModelRoot() {
        val builder=LocalNetworkSceneBuilder()
        val local=builder.build(report(NetworkBrowseMode.LOCAL),labels)
        val ownerPoint=point(local,NetworkSceneNodeKey.File(g))
        assertNotEquals(0.0,ownerPoint.x,0.001)
        // The first file becomes the internal model root, at its existing negative-world position.
        val library=builder.build(report(NetworkBrowseMode.LIBRARY,listOf(g,t,s)),labels)
        assertEquals(ownerPoint,point(library,NetworkSceneNodeKey.File(g)))
        assertEquals(setOf(s,t,g),library.nodes.values.filterIsInstance<NetworkSceneNodeKey.File>().map {it.documentId}.toSet())
        val viewKey=UUID.randomUUID().toString()
        val independent=builder.build(report(NetworkBrowseMode.LIBRARY,listOf(g,t),concept=false).copy(focusDocumentId=viewKey),labels)
        independent.document.validate()
        assertTrue(independent.nodes.values.none {it==NetworkSceneNodeKey.File(viewKey)})
        assertEquals(ownerPoint,point(independent,NetworkSceneNodeKey.File(g)))
        val collection=builder.build(report(NetworkBrowseMode.FILES,listOf(t,g,s),centres=setOf(t,g)),labels)
        assertEquals(ownerPoint,point(collection,NetworkSceneNodeKey.File(g)))
        assertEquals(point(local,NetworkSceneNodeKey.File(t)),point(collection,NetworkSceneNodeKey.File(t)))
    }

    @Test fun anEmptyOverviewHasNoFileIdentityOrSelectableDummyNode() {
        val scene=LocalNetworkSceneBuilder().build(report(NetworkBrowseMode.LIBRARY,emptyList(),false),labels)
        scene.document.validate();assertTrue(scene.nodes.isEmpty());assertTrue(scene.edges.isEmpty())
        assertEquals(labels.empty,scene.document.root.text)
        val host=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val p=LocalNetworkPresentation().apply {visible=true;reconcile(scene)}
            val v=MindMapView(host.get()).apply {document=scene.document;readOnlyNavigation=true;automaticInitialFit=false
                onSelect={id->p.selectNode(id?.let(scene.nodes::get))}}
            host.get().setContentView(v);shadowOf(Looper.getMainLooper()).idle();v.layout(0,0,1000,700);v.fit()
            val center=v.screenPoint(v.document.root.bounds.center);val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val e=MotionEvent.obtain(now,now+action,action,center.x.toFloat(),center.y.toFloat(),0)
                try {assertTrue(v.onTouchEvent(e))} finally {e.recycle()}
            }
            assertNull(p.selectedNode);assertNull(p.selectedEdge)
        } finally {host.pause().stop().destroy()}
    }

    @Test fun overviewCollectionAndEmptyTransitionsPreserveTheActualCameraWithoutRestoringAnOldSelection() {
        val host=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val builder=LocalNetworkSceneBuilder();val scene=builder.build(report(NetworkBrowseMode.LIBRARY),labels)
            val p=LocalNetworkPresentation().apply {visible=true;reconcile(scene)}
            val v=MindMapView(host.get()).apply {document=scene.document;readOnlyNavigation=true;automaticInitialFit=false}
            host.get().setContentView(v);shadowOf(Looper.getMainLooper()).idle();v.layout(0,0,1000,700)
            val token=p.attach();v.documentEpoch=token.epoch
            val geometry=LocalNetworkGeometry(v.width,v.height,v.resources.displayMetrics.density)
            p.layout(token,geometry,0);assertTrue(applyLocalNetworkCamera(p,v,p.prepareCamera(token,geometry)!!))
            v.zoomBy(1.3f);v.panText(73f,-27f);val viewport=v.viewport()
            assertTrue(p.captureCamera(token,LocalNetworkCamera(viewport,v.width,v.height,v.resources.displayMetrics.density)))
            assertTrue(p.selectNode(NetworkSceneNodeKey.File(t)))
            val collection=builder.build(report(NetworkBrowseMode.FILES,listOf(t,g,s),centres=setOf(t)),labels)
            p.invalidateInput();p.reconcile(collection);v.document=collection.document
            assertEquals(NetworkSceneNodeKey.File(t),p.selectedNode);assertEquals(viewport,v.viewport())
            val empty=builder.build(report(NetworkBrowseMode.LIBRARY,emptyList(),false),labels)
            p.reconcile(empty);v.document=empty.document;assertNull(p.selectedNode)
            val again=builder.build(report(NetworkBrowseMode.LIBRARY),labels);p.reconcile(again);v.document=again.document
            assertNull(p.selectedNode);assertEquals(viewport,v.viewport())
            scene.nodes.forEach {(id,key)->assertEquals(scene.document.node(id).bounds,point(again,key))}
        } finally {host.pause().stop().destroy()}
    }
}
