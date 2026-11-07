package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OwnedTargetPresenceTest {
    private fun scenario(block:(File)->Unit) {
        val dir=Files.createTempDirectory("owned-presence-").toFile()
        try {block(dir)} finally {dir.deleteRecursively()}
    }
    @Test fun onlyAnAccessibleRootAndExistingParentCanProveAnAbsentLeaf() = scenario {root->
        val leaf=File(root,"missing.xnote")
        assertTrue(OwnedTargetPresence.confirmedMissing(root,leaf))
        leaf.writeText("existing")
        assertFalse(OwnedTargetPresence.confirmedMissing(root,leaf))
        assertFalse(OwnedTargetPresence.confirmedMissing(null,leaf))
        assertFalse(OwnedTargetPresence.confirmedMissing(File(root,"unavailable"),File(root,"unavailable/note.xnote")))
        assertFalse(OwnedTargetPresence.confirmedMissing(root,File(root,"missing-parent/note.xnote")))
        assertFalse(OwnedTargetPresence.confirmedMissing(root,File(root.parentFile,"outside-${root.name}.xnote")))
    }
    @Test fun accessDeniedAndOtherAttributeErrorsAreNotProofOfAbsence() = scenario {root->
        val leaf=File(root,"missing.xnote")
        assertFalse(OwnedTargetPresence.confirmedMissing(root,leaf,attributes={path->
            if(path==leaf.toPath()) throw AccessDeniedException(path.toString())
            Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        }))
        assertFalse(OwnedTargetPresence.confirmedMissing(root,leaf,attributes={throw java.io.IOException("unavailable mount")}))
    }
    @Test fun symbolicLinksCannotMasqueradeAsAnAbsentOwnedTarget() = scenario {root->
        val leaf=File(root,"dangling.xnote")
        Files.createSymbolicLink(leaf.toPath(),File(root,"elsewhere.xnote").toPath())
        assertFalse(OwnedTargetPresence.confirmedMissing(root,leaf))
        val directory=File(root,"directory").apply {mkdir()};val alias=File(root,"alias")
        Files.createSymbolicLink(alias.toPath(),directory.toPath())
        assertFalse(OwnedTargetPresence.confirmedMissing(root,File(alias,"missing.xnote")))
    }
    @Test fun unavailableExternalRootDoesNotFallBackOrCreateARecoveryDestination() = scenario {root->
        var internalReads=0
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalFilesDir(type:String?):File?=null
            override fun getFilesDir():File {internalReads++;return File(root,"fallback")}
        }
        assertNull(AppStorageDocumentsProvider.recoveryRoot(context));assertEquals(0,internalReads)
        assertFalse(File(root,"fallback").exists())
        val external=File(root,"external").apply {mkdir()}
        val available=object:ContextWrapper(RuntimeEnvironment.getApplication()) {override fun getExternalFilesDir(type:String?)=external}
        val destination=AppStorageDocumentsProvider.recoveryRoot(available)!!
        assertFalse(destination.exists()) // Merely checking availability must not create the root.
    }
    @Test fun ownedRecoveryIoNeverReadsOrWritesTheProviderFallbackRoot() = scenario {base->
        val external=File(base,"external").apply {mkdir()};var mounted:File?=external
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalFilesDir(type:String?)=mounted
            override fun getFilesDir()=File(base,"internal").apply {mkdir()}
        }
        val realRoot=AppStorageDocumentsProvider.rootDir(context)
        val original=File(realRoot,"note.xnote").apply {writeText("owned original")}
        mounted=null
        val fallbackRoot=AppStorageDocumentsProvider.rootDir(context)
        val other=File(fallbackRoot,"note.xnote").apply {writeText("different fallback document")}
        mounted=external
        val target=OwnedRecoveryTarget(context,"root/note.xnote")
        assertEquals("owned original",target.read().use {it.reader().readText()})
        mounted=null
        assertFalse(target.confirmedMissing())
        assertThrows(java.io.FileNotFoundException::class.java) {target.read()}
        assertThrows(java.io.FileNotFoundException::class.java) {target.write(original)}
        assertEquals("different fallback document",other.readText());assertEquals("owned original",original.readText())
    }
    @Test fun ownedBackendCanRecreateAConfirmedMissingLeafButNotEscapeItsRoot() = scenario {base->
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {override fun getExternalFilesDir(type:String?)=base}
        val root=AppStorageDocumentsProvider.rootDir(context)
        val target=OwnedRecoveryTarget(context,"root/restored.xnote")
        val source=File(base,"backup").apply {writeText("restore bytes")}
        assertTrue(target.confirmedMissing());target.write(source)
        assertEquals("restore bytes",File(root,"restored.xnote").readText());assertFalse(target.confirmedMissing())
        val escape=OwnedRecoveryTarget(context,"root/../../outside.xnote")
        assertFalse(escape.confirmedMissing())
        assertThrows(IllegalArgumentException::class.java) {escape.write(source)}
        assertFalse(File(base.parentFile,"outside.xnote").exists())
    }

}
