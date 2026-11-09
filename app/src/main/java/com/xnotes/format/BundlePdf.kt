package com.xnotes.format

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Byte-preserving PDF packaging; an independent copy also receives fresh document/page IDs. */
object BundlePdf {
    const val ENTRY = "assets/source.pdf"

    fun write(source:File,out:OutputStream,identity:BundleIdentity,pdf:File?,external:Boolean,cancelled:()->Boolean={false}) {
        BundleSnapshot.take(source,source.absoluteFile.parentFile,cancelled).use {snapshot->
            write(snapshot,out,identity,pdf,external,cancelled)
            check(BundleMigration.hash(source,cancelled)==snapshot.sourceHash) {"Source changed during portable export"}
        }
    }

    /** Reuse an already owned stream snapshot rather than copying a large PDF bundle a second time. */
    internal fun write(snapshot:BundleSnapshot,out:OutputStream,identity:BundleIdentity,pdf:File?,external:Boolean,cancelled:()->Boolean={false}) {
        val target=identity.copy(externalPdf=external)
        BundleCopyPlan.prepare(snapshot,target,requireNotNull(snapshot.file.parentFile),cancelled).use {plan->
            if(identity.externalPdf) requireNotNull(pdf)
            write(snapshot,plan,out,pdf,external,cancelled)
        }
    }

    private fun write(snapshot:BundleSnapshot,plan:BundleCopyPlan,out:OutputStream,pdf:File?,external:Boolean,cancelled:()->Boolean) {
        if(external || plan.header.identity?.externalPdf==true) requireNotNull(pdf)
        pdf?.let {require(BundleMigration.hash(it,cancelled)==plan.identity.pdfResource) {"PDF original changed"}}
        val expected=plan.before.toMutableMap()
        plan.manifest?.let {expected[BundleCopyPlan.MANIFEST]=BundleMigration.hash(it,cancelled)}
        ZipFile(snapshot.file).use {zip->ZipOutputStream(out).use {dest->
            dest.setLevel(1);zip.comment?.let(dest::setComment)
            for(entry in zip.entries().asSequence()) {
                check(!cancelled())
                if(entry.name==BundleIdentity.ENTRY || entry.name==ENTRY) continue
                val replacement=entry.name==BundleCopyPlan.MANIFEST && plan.manifest!=null || entry.name==AnchorMetadata.ENTRY
                dest.putNextEntry(ZipEntry(entry.name).apply {
                    time=entry.time
                    if(!replacement && entry.method==ZipEntry.STORED) {method=ZipEntry.STORED;size=entry.size;crc=entry.crc}
                })
                if(entry.name==AnchorMetadata.ENTRY) dest.write(requireNotNull(plan.marker)) else {
                    val input=if(entry.name==BundleCopyPlan.MANIFEST) plan.manifest?.inputStream() ?: zip.getInputStream(entry) else zip.getInputStream(entry)
                    val digest=input.use {BundleEntries.copy(it,dest,cancelled)}
                    require(digest==expected[entry.name]) {"Snapshot payload changed during export"}
                }
                dest.closeEntry()
            }
            if(!external) {
                if(pdf!=null) {
                    val crc=java.util.zip.CRC32()
                    pdf.inputStream().use {input->
                        val buffer=ByteArray(65536)
                        while(true) {check(!cancelled());val n=input.read(buffer);if(n<0) break;crc.update(buffer,0,n)}
                    }
                    dest.putNextEntry(ZipEntry(ENTRY).apply {method=ZipEntry.STORED;size=pdf.length();compressedSize=size;this.crc=crc.value})
                    val written=pdf.inputStream().use {BundleEntries.copy(it,dest,cancelled)}
                    require(written==plan.identity.pdfResource) {"PDF changed during export"}
                    dest.closeEntry()
                } else zip.getEntry(ENTRY)?.let {entry->
                    val expectedPdf=requireNotNull(expected[ENTRY])
                    plan.identity.pdfResource?.let {require(it==expectedPdf) {"PDF identity does not match embedded original"}}
                    dest.putNextEntry(ZipEntry(ENTRY).apply {
                        time=entry.time
                        if(entry.method==ZipEntry.STORED) {method=ZipEntry.STORED;size=entry.size;crc=entry.crc}
                    })
                    require(zip.getInputStream(entry).use {BundleEntries.copy(it,dest,cancelled)}==expectedPdf)
                    dest.closeEntry()
                }
            }
            plan.identity.write(dest)
        }}
    }

    fun prepare(source:File,directory:File,identity:BundleIdentity,pdf:File,cancelled:()->Boolean={false}):BundleMigration.Prepared =
        BundleSnapshot.take(source,directory,cancelled).use {snapshot->
            require(BundleHeader.read(snapshot.file,cancelled).identity?.documentId==identity.documentId) {"PDF migration source identity changed"}
            val target=identity.copy(externalPdf=true)
            BundleCopyPlan.prepare(snapshot,target,directory,cancelled).use {plan->
                require(plan.before[ENTRY]==identity.pdfResource) {com.xnotes.storage.StorageText.t(com.xnotes.storage.StorageMsg.PDF_RESOURCE_VERIFY_FAILED)}
                val candidate=File.createTempFile("pdf-reference-",".zip",directory)
                try {
                    FileOutputStream(candidate).use {write(snapshot,plan,it,pdf,true,cancelled)}
                    java.io.RandomAccessFile(candidate,"rw").use {it.fd.sync()}
                    plan.verify(candidate,cancelled,pdfDigest=null)
                    check(BundleMigration.hash(source,cancelled)==snapshot.sourceHash) {"Source changed during PDF migration"}
                    BundleMigration.Prepared(candidate,target,snapshot.sourceHash)
                } catch(error:Throwable) {candidate.delete();throw error}
            }
        }
}
