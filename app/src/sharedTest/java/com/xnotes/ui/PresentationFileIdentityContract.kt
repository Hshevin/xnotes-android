package com.xnotes.ui

import com.xnotes.mindmap.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Run against Android's actual filesystem and a host provider with Android's timestamp semantics. */
abstract class PresentationFileIdentityContract {
    @get:Rule val tmp=TemporaryFolder()
    protected open fun attributes(path:Path):BasicFileAttributes =
        Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
    private fun store()=LastPresentationStore(tmp.root,object:LastPresentationStore.FileOps {
        override fun attributes(path:Path)=this@PresentationFileIdentityContract.attributes(path)
        override fun openRead(path:Path):InputStream=Files.newInputStream(path,READ,NOFOLLOW_LINKS)
        override fun replace(stage:Path,target:Path) {Files.move(stage,target,ATOMIC_MOVE,REPLACE_EXISTING)}
        override fun deleteIfExists(path:Path) {Files.deleteIfExists(path)}
    })
    private fun head()=File(tmp.root,"last-presentation.json")
    private fun pending()=tmp.root.listFiles()!!.filter {it.name.startsWith(".last-presentation-")}

    @Test fun siblingChangesDoNotReplaceTheDirectoryOrInvalidateItsMarker()=runBlocking {
        val store=store()
        val observation=store.observe()
        File(tmp.root,"unrelated-session").writeText("keep")
        Files.setLastModifiedTime(tmp.root.toPath(),FileTime.fromMillis(1000))
        assertTrue("A sibling write is not a root replacement",store.matches(observation))
        val gate=PreparedHomeGate.prepare(store,store.reserve())
        assertNotNull("The real presentation gate must prepare",gate)
        try {assertTrue(gate!!.commit {true})} finally {gate!!.close()}
        assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
        assertEquals("keep",File(tmp.root,"unrelated-session").readText())
        assertTrue(pending().isEmpty())
    }

    @Test fun writingAndReplacingAMarkerDoesNotInvalidateItsOwnStageOrNextAttempt()=runBlocking {
        val store=store()
        repeat(3) {
            val attempt=store.begin(store.reserve(),LastPresentation.Home)
            try {
                assertEquals(LastPresentationPrepare.Ready,attempt.prepare())
                assertEquals(LastPresentationWrite.Written,attempt.commit {true})
            } finally {attempt.close()}
            assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
            assertTrue(pending().isEmpty())
        }
    }

    @Test fun failedBusinessCheckKeepsTheHeadAndCleansOnlyItsOwnStage()=runBlocking {
        head().writeBytes(LastPresentationCodec.encode(LastPresentation.Home))
        val before=head().readBytes()
        val foreign=File(tmp.root,"foreign.pending").apply {writeText("keep")}
        val store=store();val attempt=store.begin(store.reserve(),LastPresentation.Home)
        try {
            assertEquals(LastPresentationPrepare.Ready,attempt.prepare())
            assertEquals(LastPresentationWrite.Changed,attempt.commit {false})
        } finally {attempt.close()}
        assertArrayEquals(before,head().readBytes());assertEquals("keep",foreign.readText())
        assertTrue(pending().isEmpty())
    }

    @Test fun replacingTheDirectoryCannotReuseAnOldStore()=runBlocking {
        val root=File(tmp.root,"private").apply {mkdir()}
        val store=LastPresentationStore(root)
        Files.move(root.toPath(),File(tmp.root,"retained-old-root").toPath())
        assertTrue(root.mkdir())
        val foreign=File(root,"last-presentation.json").apply {writeText("foreign")}
        val attempt=store.begin(store.reserve(),LastPresentation.Home)
        try {
            assertNotEquals(LastPresentationPrepare.Ready,attempt.prepare())
            assertNotEquals(LastPresentationWrite.Written,attempt.commit {true})
        } finally {attempt.close()}
        assertEquals("foreign",foreign.readText())
    }

    @Test fun successiveEditsRemainRecoverableAndReadOnlyObservationDoesNotPublishThem() {
        val a=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="A")))
        val b=a.copy(nodes=a.nodes.map {it.copy(text="B")})
        val c=a.copy(nodes=a.nodes.map {it.copy(text="C")})
        val original=MapCodec.encode(a)
        val target=object:MapTarget {
            override fun read()=original.clone()
            override fun write(bytes:ByteArray) {throw AssertionError("Local recovery must not overwrite the original")}
        }
        fun writer()=MapStore(tmp.root,"edited-map",target,emptySet()) {attributes(it.toPath())}
        val store=writer();assertEquals(a,store.open().document)
        store.retainForRecovery(b)
        assertEquals(b,writer().observe().document)
        store.retainForRecovery(c)
        store.retainForRecovery(c)
        assertEquals(c,writer().observeForIdentity(a.id).document)
        val reopened=writer();val result=reopened.open(a.id)
        assertTrue(result.recovered);assertFalse(result.conflict);assertEquals(c,result.document)
        assertTrue(reopened.verifyOpenedRecovery(requireNotNull(reopened.openedRecoveryProof)))
        assertArrayEquals(original,target.read())
        assertTrue(tmp.root.listFiles()!!.none {it.name.endsWith(".pending")})
    }

    @Test fun openingAnAlreadyWrittenPendingMapNormalizesRecoveryWithoutWritingTheOriginal() {
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Saved map")))
        val bytes=MapCodec.encode(doc)
        val target=object:MapTarget {
            override fun read()=bytes.clone()
            override fun write(bytes:ByteArray) {throw AssertionError("Open must not write the original")}
        }
        fun writer()=MapStore(tmp.root,"map",target,emptySet()) {attributes(it.toPath())}
        writer().apply {open();retainForRecovery(doc)}
        val reopened=writer()
        assertEquals(doc,reopened.open(doc.id).document)
        val proof=requireNotNull(reopened.openedRecoveryProof)
        assertTrue(reopened.verifyOpenedRecovery(proof))
        assertTrue(tmp.root.listFiles()!!.none {it.name.endsWith(".pending")})
        assertArrayEquals(bytes,target.read())
    }
}
