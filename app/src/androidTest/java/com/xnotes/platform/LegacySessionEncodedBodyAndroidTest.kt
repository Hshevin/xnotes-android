package com.xnotes.platform

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The saved-bytes working-copy handoff on the actual Android filesystem: copy, fsync, hash proof,
 * replacement and cleanup. JVM/Robolectric NIO is not evidence for these.
 */
class LegacySessionEncodedBodyAndroidTest {
    private val codec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("legacy-encoded-body-android-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    private fun encoded(root:File):Pair<InfiniteDocument,File> {
        val fixed=InfiniteDocument().snapshotForWrite(fixedItems=true)
        return fixed to File(root,"save.tmp").apply {outputStream().use {codec.write(fixed,it)}}
    }
    private fun leftovers(dir:File)=dir.listFiles().orEmpty().filter {it.name.endsWith(".tmp") || it.name.endsWith(".pending")}.map {it.name}

    @Test fun savedBytesReplaceAnExistingCanvasBodyAndLeaveNoCandidates()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        assertEquals(LegacySessionWrite.SAVED,CanvasSessionStore(slot,codec,File(root,"img")).saveChecked(InfiniteDocument(),
            CanvasSessionStore.Metadata(null,"old",true)))
        val (fixed,file)=encoded(root)
        val bytes=file.readBytes();val sha=BundleMigration.hash(file)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,file,sha,fixed.snapshotOwnerToken,fixed.identity.documentId,
            CanvasSessionStore.Metadata("content://tree/c","c.xcanvas",false)).use {prepared->
            assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Published)
        }
        assertArrayEquals(bytes,File(slot,"canvas.xcanvas").readBytes())
        assertArrayEquals(bytes,file.readBytes())
        assertEquals(emptyList<String>(),leftovers(slot))
    }

    @Test fun aHashMismatchKeepsTheExistingBodyAndCleansItsCandidate()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        assertEquals(LegacySessionWrite.SAVED,CanvasSessionStore(slot,codec,File(root,"img")).saveChecked(InfiniteDocument(),
            CanvasSessionStore.Metadata(null,"old",true)))
        val before=File(slot,"canvas.xcanvas").readBytes()
        val (fixed,file)=encoded(root)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,file,"0".repeat(64),fixed.snapshotOwnerToken,fixed.identity.documentId,
            CanvasSessionStore.Metadata("content://tree/c","c.xcanvas",false)).use {prepared->
            assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Failed)
        }
        assertArrayEquals(before,File(slot,"canvas.xcanvas").readBytes())
        assertEquals(emptyList<String>(),leftovers(slot))
    }
}
