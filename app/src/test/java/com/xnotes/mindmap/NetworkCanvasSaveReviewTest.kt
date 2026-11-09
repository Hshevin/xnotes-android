package com.xnotes.mindmap

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkCanvasSaveReviewTest {
    private suspend fun <T> main(block:()->T):T=withContext(Dispatchers.Main) {block()}
    private suspend fun until(block:()->Boolean)=withTimeout(5000L) {while(!main(block)) delay(1)}
    private fun selection()=NetworkCanvasSelection(listOf(MapFileCardSeed(UUID.randomUUID().toString(),"Source",0.0,0.0))) {true}
    private fun canvas()=MindMapDocument(nodes=listOf(MapNode(id="body",parent=null,free=true,text="Body")),organization=MapOrganization.RELATION_CANVAS)

    @Test fun aChangedSaveAfterTheCardsWereAddedKeepsTheSuccessAndLetsAutosavePublishLaterEdits()=MapBacklinkLibraryTest().scenario {f->
        val file=f.graph("changed-save.xmap",canvas());val uri=f.uri(file);val lease=f.acquire(uri)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=main {NetworkToCanvasController(f.context,selection(),scope)}
        val lock=MapSaves.lockFor(uri);lock.lock();var held=true
        try {
            main {assertTrue(controller.chooseExisting(uri));assertTrue(controller.confirm())}
            until {lease.session.saving && controller.nodeIds.isNotEmpty()}
            main {assertTrue(lease.session.commit(lease.session.document!!.update("body") {it.copy(text="Later edit")}))}
            lock.unlock();held=false
            until {!controller.busy}
            main {
                assertTrue(controller.saveOutcome is MapSaveOutcome.Changed)
                assertEquals(NetworkCanvasStep.ADDED,controller.step);assertFalse(controller.problem)
                assertTrue(controller.canView);assertFalse(controller.canRetrySave)
            }
            // This fixture pumps due Main messages without advancing Android's virtual clock.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
            until {!lease.session.dirty && !lease.session.saving}
            assertEquals("Later edit",MapCodec.decode(file.readBytes()).node("body").text)
        } finally {if(held) lock.unlock();main {controller.close()}?.join();scope.cancel()}
    }

    @Test fun confirmationCanRetryAfterABusyTargetHasReloadedItsInteractionEpoch()=MapBacklinkLibraryTest().scenario {f->
        val file=f.graph("busy-retry.xmap",canvas());val uri=f.uri(file);val lease=f.acquire(uri)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=main {NetworkToCanvasController(f.context,selection(),scope)}
        try {
            main {lease.session.inkContact=true;assertTrue(controller.chooseExisting(uri));assertTrue(controller.confirm())}
            until {!controller.busy}
            main {assertEquals(NetworkCanvasStep.CONFIRM,controller.step);lease.session.inkContact=false;lease.session.useFileVersion()}
            until {!lease.session.replacingDocument}
            main {assertTrue(controller.confirm())}
            until {!controller.busy}
            main {assertEquals(NetworkCanvasStep.ADDED,controller.step);assertFalse(controller.problem)}
            assertEquals(1,MapCodec.decode(file.readBytes()).nodes.count {it.fileCardId!=null})
        } finally {main {lease.session.inkContact=false;controller.close()}?.join();scope.cancel()}
    }
}
