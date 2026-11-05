package com.xnotes.pen

import android.content.ContextWrapper
import com.xnotes.storage.DocumentRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DocumentRegistryTest {
    private fun database(block: (DocumentRegistry,File)->Unit) {
        val base=PenTestRunner.instance.targetContext
        val root=File(base.cacheDir,"registry-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(base) { override fun getNoBackupFilesDir()=root }
        val db=DocumentRegistry(context)
        try { block(db,root) } finally { db.close(); root.deleteRecursively() }
    }
    @Test fun duplicateIdentityIsExplicitAndMoveKeepsIdentity() = database { db,_ ->
        val id=UUID.randomUUID().toString()
        db.register(id,"xnote","note","content://example/document/a")
        db.moved("content://example/document/a","content://example/document/b")
        assertEquals(id,db.idAt("content://example/document/b"))
        db.register(id,"xnote","external copy","content://example/document/c")
        try { db.resolve(id); fail("must report conflict") } catch(_:IllegalStateException) {}
        db.markUnavailable("content://example/document/c")
        assertEquals("content://example/document/b",db.resolve(id))
    }
    @Test fun uriAliasesDoNotBecomeFalseCopies() = database { db,_ ->
        val id=UUID.randomUUID().toString()
        db.register(id,"xnote","note","content://example/tree/root/document/a")
        db.register(id,"xnote","note","content://example/document/a")
        assertEquals(1,db.locations(id).size)
    }
    @Test fun moveReusesStaleDestinationAndAliasCanBeMarkedUnavailable() = database { db,_ ->
        val id = UUID.randomUUID().toString()
        val oldId = UUID.randomUUID().toString()
        db.register(id,"xnote","source","content://example/document/a")
        db.register(oldId,"xnote","old target","content://example/document/b")
        db.markUnavailable("content://example/tree/root/document/b")
        assertTrue(db.locations(oldId).isEmpty())
        db.moved("content://example/document/a","content://example/document/b")
        assertEquals(id,db.idAt("content://example/document/b"))
        assertEquals("content://example/document/b",db.resolve(id))
    }
    @Test fun referencesUndoAndActiveLeaseProtectOwnedResource() = database { db,root ->
        val file=File(root,"resource.pdf").apply { writeText("immutable") }
        val resource=DocumentRegistry.Resource("id","paper",file.path,file.length(),"digest")
        db.registerResource(resource)
        db.references("chat:one",setOf("id"))
        assertEquals(0,db.collect(Long.MAX_VALUE/2,0,root))
        db.references("undo:one",setOf("id")); db.references("chat:one",emptySet())
        assertEquals(0,db.collect(Long.MAX_VALUE/2,0,root))
        val lease=db.lease("id",1)
        db.references("undo:one",emptySet())
        assertEquals(0,db.collect(Long.MAX_VALUE/2,0,root))
        db.release(lease)
        assertEquals(1,db.collect(Long.MAX_VALUE/2,0,root)); assertFalse(file.exists())
    }
    @Test fun failedReferenceChangePreservesPreviousOwnerAndNeverDeletesExternalFiles() = database { db,root ->
        val file=File(root,"external.pdf").apply { writeText("original") }
        db.registerResource(DocumentRegistry.Resource("id","paper",file.path,file.length(),"digest",owned=false))
        db.references("note:one",setOf("id"))
        try { db.references("note:one",setOf("missing")); fail() } catch(_:IllegalStateException) {}
        assertEquals(setOf("id"),db.referenceIds("note:one"))
        db.references("note:one",emptySet())
        assertEquals(0,db.collect(Long.MAX_VALUE/2,0,root)); assertEquals("original",file.readText())
    }
    @Test fun sharedOriginalSurvivesOneNoteDeletionAndPortableExportLease() = database { db, root ->
        val pdf = File(root, "shared.pdf").apply { writeText("%PDF-1.7 original") }
        val id = com.xnotes.format.BundleMigration.hash(pdf)
        db.registerResource(DocumentRegistry.Resource(id, "PDF", pdf.path, pdf.length(), id))
        db.references("note:first", setOf(id))
        db.references("note:second", setOf(id))
        db.references("note:first", emptySet())
        assertEquals(0, db.collect(Long.MAX_VALUE / 2, 0, root))
        assertEquals(id, com.xnotes.format.BundleMigration.hash(pdf))
        val exportLease = db.lease(id, 1)
        db.references("note:second", emptySet())
        assertEquals(0, db.collect(Long.MAX_VALUE / 2, 0, root))
        db.release(exportLease)
        assertEquals(1, db.collect(Long.MAX_VALUE / 2, 0, root))
    }

    @Test fun legacyNoteMigratesOnceAndPortableExportRestoresOriginalPayload() {
        val context = PenTestRunner.instance.targetContext
        val storage = com.xnotes.storage.NotebookStorage.get(context)
        val resources = com.xnotes.storage.PdfResources.get(context)
        val root = com.xnotes.platform.AppStorageDocumentsProvider.treeUri(context)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, "root")
        val uri = android.provider.DocumentsContract.createDocument(context.contentResolver, parent,
            "application/octet-stream", "migration-${UUID.randomUUID()}.xnote")!!
        val pdfBytes = "%PDF-1.7 test-${UUID.randomUUID()}".toByteArray()
        val originalIdentity = com.xnotes.format.BundleIdentity()
        val file = storage.ownedFile(uri.toString())!!
        try {
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, bytes) in listOf("manifest.json" to """{"format":"xnote","has_pdf":true,"pages":[],"future":1.0000}""".toByteArray(),
                    com.xnotes.format.BundlePdf.ENTRY to pdfBytes, "unknown.bin" to byteArrayOf(1, 2, 3))) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                originalIdentity.write(zip)
            }
            val before = com.xnotes.format.BundleMigration.fingerprints(file)
            val identity = storage.ensure(uri.toString(), "test.xnote", "xnote")
            assertEquals(originalIdentity.documentId, identity.documentId)
            assertTrue(identity.externalPdf)
            assertFalse(com.xnotes.format.BundleMigration.fingerprints(file).containsKey(com.xnotes.format.BundlePdf.ENTRY))
            val original = resources.resolve(identity.pdfResource!!)
            assertArrayEquals(pdfBytes, original.readBytes())
            assertEquals(setOf(identity.pdfResource), resources.references("note:${identity.documentId}"))
            val migrated = file.readBytes()
            assertEquals(identity, storage.ensure(uri.toString(), "test.xnote", "xnote"))
            assertArrayEquals(migrated, file.readBytes())
            val exported = File.createTempFile("portable-", ".xnote", context.cacheDir)
            try {
                exported.outputStream().use { com.xnotes.format.BundlePdf.write(file, it, identity, original, false) }
                assertEquals(before.filterKeys { it != com.xnotes.format.BundleIdentity.ENTRY },
                    com.xnotes.format.BundleMigration.fingerprints(exported).filterKeys { it != com.xnotes.format.BundleIdentity.ENTRY })
                assertFalse(com.xnotes.format.BundleIdentity.read(exported)!!.externalPdf)
            } finally { exported.delete() }
        } finally {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            storage.registry.markUnavailable(uri.toString())
            resources.pin("note:${originalIdentity.documentId}", emptySet())
        }
    }

}
