package com.xnotes.storage

import android.content.Context
import android.content.ContextWrapper
import com.xnotes.pen.PenTestRunner
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Retained platform regressions for Android SQLite TEXT and CursorWindow behavior. */
class OriginalPdfNetworkMetadataAndroidTest {
    private class Fixture {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"original-network-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(PenTestRunner.instance.targetContext) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=root
        }
        val registry=DocumentRegistry(context)
        val catalog=OriginalPdfCatalog(registry)
        data class Seed(val id:String,val sha:String,val pages:List<String>)
        fun seed():Seed {
            val id=UUID.randomUUID().toString();val sha=id.replace("-","").repeat(2)
            val file=File(root,"ai-pdfs/$sha.pdf").apply {parentFile.mkdirs();writeText("metadata must not decode or hash these bytes")}
            val db=registry.writableDatabase
            db.execSQL("INSERT INTO resources(id,name,path,size,digest,version,owned,created) VALUES(?,?,?,?,?,1,1,0)",arrayOf(sha,"Original",file.path,file.length(),sha))
            db.execSQL("INSERT INTO original_pdf_documents VALUES(?,?,?,2,1,0,1)",arrayOf(id,sha,"Original"))
            val pages=List(2) {UUID.randomUUID().toString()}
            pages.forEachIndexed {i,page->db.execSQL("INSERT INTO original_pdf_pages VALUES(?,?,?)",arrayOf(id,i,page))}
            db.execSQL("INSERT INTO resource_refs VALUES(?,?)",arrayOf(OriginalPdfCatalog.owner(id),sha))
            return Seed(id,sha,pages)
        }
        fun close() {registry.close();root.deleteRecursively()}
        fun pins():Int=registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM resource_refs",null).use {it.moveToFirst();it.getInt(0)}
    }
    @Test fun embeddedNulInAnyPageIsRejectedWithoutChangingOtherOriginalsOrPins() {
        val f=Fixture()
        try {
            val first=f.seed();val later=f.seed();val good=f.seed()
            for((seed,index) in listOf(first to 0,later to 1)) f.registry.writableDatabase.execSQL(
                "UPDATE original_pdf_pages SET page_id=? WHERE document_id=? AND ordinal=?",arrayOf(seed.pages[index]+0.toChar()+"suffix",seed.id,index))
            val result=f.catalog.networkMetadata(setOf(first.id,later.id,good.id))
            assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(first.id).state)
            assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(later.id).state)
            assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(good.id).state)
            assertEquals(good.pages[0],result.files.getValue(good.id).header!!.firstPageId)
            assertEquals(3,f.pins())
        } finally {f.close()}
    }
    @Test fun aShortEmbeddedNulTitleKeepsItsActualTextWithoutWeakeningPageValidation() {
        val f=Fixture()
        try {
            val seed=f.seed();val title="A"+0.toChar()+"B"
            f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET title=? WHERE id=?",arrayOf(title,seed.id))
            val result=f.catalog.networkMetadata(setOf(seed.id)).files.getValue(seed.id)
            assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.state);assertEquals(title,result.header!!.title)
            assertEquals(1,f.pins())
        } finally {f.close()}
    }
    @Test fun oversizedRawMetadataDoesNotOverflowTheSharedWindowOrPoisonAGoodOriginal() {
        val f=Fixture()
        try {
            val title=f.seed();val path=f.seed();val good=f.seed();val huge="x".repeat(3*1024*1024)
            f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET title=? WHERE id=?",arrayOf(huge,title.id))
            f.registry.writableDatabase.execSQL("UPDATE resources SET path=? WHERE id=?",arrayOf("/"+huge,path.sha))
            val result=f.catalog.networkMetadata(setOf(title.id,path.id,good.id),orphanLimit=2)
            assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(title.id).state)
            assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(path.id).state)
            assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(good.id).state)
            assertEquals(good.pages[0],result.files.getValue(good.id).header!!.firstPageId)
            assertEquals(3,f.pins())
            f.registry.readableDatabase.rawQuery("SELECT length(title) FROM original_pdf_documents WHERE id=?",arrayOf(title.id)).use {
                assertTrue(it.moveToFirst());assertEquals(huge.length,it.getInt(0))
            }
        } finally {f.close()}
    }
}
