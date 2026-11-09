package com.xnotes.mindmap

import android.app.Activity
import android.net.Uri
import android.os.Looper
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
class NetworkExpansionPresentationTest {
    private class Fixture:AutoCloseable {
        val host=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=host.get()
        val focus=UUID.randomUUID().toString();val graph=UUID.randomUUID().toString();val t=UUID.randomUUID().toString()
        val location=BacklinkLocation(Uri.parse("content://network-expansion/document/graph"),emptySet(),"location",graph,"Graph.xmap")
        val labels=LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs")
        val owner=Any()
        val presentation=LocalNetworkPresentation()
        val builder=LocalNetworkSceneBuilder()
        val view=MindMapView(activity).apply {automaticInitialFit=false;readOnlyNavigation=true}
        lateinit var token:LocalNetworkAttachment
        fun concept(name:String,file:String=t):LocalNetworkConcept {
            val key=NetworkConceptKey(location.key,graph,name)
            val rows=listOf(focus,file).distinct().mapIndexed {i,id->MapBacklinkRow(location,name,"source-$i",MapSourceTarget(id,SourceScope.FILE),
                name,NodeKind.TEXT,"$name.xnote","",null,SourceStatus.UNVERIFIED,null,BacklinkOrigin.STORED)}
            return LocalNetworkConcept(key,location,name,NodeKind.TEXT,rows.map {LocalNetworkEvidence(it,owner,0)})
        }
        fun report(vararg concepts:LocalNetworkConcept):LocalFileNetworkReport {
            val ids=listOf(focus,graph)+concepts.flatMap {it.members.map {e->e.fileId}}
            return LocalFileNetworkReport(focus,ids.distinct().map {LocalNetworkFile(it,listOf(it))},concepts.toList(),emptyList(),1,true)
        }
        fun geometry()=LocalNetworkGeometry(view.width,view.height,view.resources.displayMetrics.density)
        fun camera()=LocalNetworkCamera(view.viewport(),view.width,view.height,view.resources.displayMetrics.density)
        fun show(scene:LocalNetworkScene) {presentation.reconcile(scene);view.document=scene.document}
        fun mount(scene:LocalNetworkScene) {
            presentation.visible=true;show(scene)
            activity.setContentView(view);shadowOf(Looper.getMainLooper()).idle();view.layout(0,0,1000,700)
            assertTrue(view.isAttachedToWindow)
            token=presentation.attach();view.documentEpoch=token.epoch
            view.onViewportChanged={presentation.layout(token,geometry(),0);presentation.captureCamera(token,camera())}
            presentation.layout(token,geometry(),0)
            assertTrue(applyLocalNetworkCamera(presentation,view,presentation.prepareCamera(token,geometry())!!))
        }
        fun node(scene:LocalNetworkScene,name:String)=scene.document.node(scene.nodes.entries.single {
            it.value==NetworkSceneNodeKey.Concept(NetworkConceptKey(location.key,graph,name))}.key)
        override fun close() {presentation.visible=false;host.pause().stop().destroy()}
    }

    @Test fun collapseThenAnotherExpansionCannotOccupyTheHiddenNodesOldSlotOrMoveTheActualCamera() {
        Fixture().use {f->
            val base=f.concept("Direct");val deep=f.concept("Deeper",UUID.randomUUID().toString());val other=f.concept("Other",UUID.randomUUID().toString())
            val first=f.builder.build(f.report(base,deep),f.labels);f.mount(first)
            f.view.zoomBy(1.27f);f.view.panText(71f,-39f)
            val viewport=f.view.viewport();val camera=f.presentation.camera
            val directBounds=f.node(first,"Direct").bounds;val deepBounds=f.node(first,"Deeper").bounds
            assertTrue(f.presentation.selectNode(NetworkSceneNodeKey.Concept(deep.key)))
            val old=f.presentation.captureInput()!!
            f.presentation.invalidateInput()
            f.show(f.builder.build(f.report(base),f.labels))
            assertFalse(old.current());assertNull(f.presentation.selectedNode)
            val replacement=f.builder.build(f.report(base,other),f.labels);f.show(replacement)
            assertNotEquals("The newly expanded concept cannot take a retained hidden slot",deepBounds,f.node(replacement,"Other").bounds)
            assertTrue(f.presentation.selectNode(NetworkSceneNodeKey.Concept(base.key)))
            val restored=f.builder.build(f.report(base,deep,other),f.labels);f.show(restored)
            assertEquals(deepBounds,f.node(restored,"Deeper").bounds);assertEquals(directBounds,f.node(restored,"Direct").bounds)
            assertEquals(NetworkSceneNodeKey.Concept(base.key),f.presentation.selectedNode)
            assertEquals(viewport,f.view.viewport());assertEquals(camera,f.presentation.camera)
            assertTrue(f.presentation.cameraReady);assertTrue(f.presentation.idle)
        }
    }

    @Test fun aBoundedPositionCacheEvictsHiddenKeysWhileTheVisiblePointAndRecentHiddenPointStayStable() {
        Fixture().use {f->
            val builder=LocalNetworkSceneBuilder(maxPositions=8)
            val base=f.concept("Direct")
            val first=builder.build(f.report(base),f.labels)
            val original=f.node(first,"Direct").bounds
            var latest:LocalNetworkConcept?=null;var latestBounds:com.xnotes.core.geometry.Rect?=null
            repeat(12) {i->
                val next=f.concept("Expansion $i",UUID.randomUUID().toString())
                val scene=builder.build(f.report(base,next),f.labels)
                assertEquals(original,f.node(scene,"Direct").bounds)
                latest=next;latestBounds=f.node(scene,next.title).bounds
            }
            builder.build(f.report(base),f.labels)
            val returned=builder.build(f.report(base,latest!!),f.labels)
            assertEquals(original,f.node(returned,"Direct").bounds);assertEquals(latestBounds,f.node(returned,latest!!.title).bounds)
        }
    }

    @Test fun queryControlsRetireInputAndPendingFitWithoutDetachingOrChangingTheCurrentCameraAndSelection() {
        Fixture().use {f->
            val base=f.concept("Direct");f.mount(f.builder.build(f.report(base),f.labels))
            f.view.panText(91f,43f);assertTrue(f.presentation.selectNode(NetworkSceneNodeKey.Concept(base.key)))
            val camera=f.presentation.camera;val viewport=f.view.viewport();val input=f.presentation.captureInput()!!
            assertTrue(f.presentation.requestFit())
            val pending=f.presentation.prepareCamera(f.token,f.geometry())!!
            f.presentation.invalidateInput();f.presentation.invalidateInput()
            assertFalse(input.current());assertFalse(applyLocalNetworkCamera(f.presentation,f.view,pending))
            assertNull("No new automatic fit follows a controls change",f.presentation.prepareCamera(f.token,f.geometry()))
            assertTrue(f.presentation.attachmentCurrent(f.token));assertTrue(f.presentation.cameraReady)
            assertEquals(camera,f.presentation.camera);assertEquals(viewport,f.view.viewport())
            assertEquals(NetworkSceneNodeKey.Concept(base.key),f.presentation.selectedNode)
            assertNotNull(f.presentation.captureInput())
        }
    }
}
