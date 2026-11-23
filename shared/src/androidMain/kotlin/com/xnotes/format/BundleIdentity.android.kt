package com.xnotes.format

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Identity metadata is separate; page identity copies use lossless manifest edits, never model encoding. */
actual data class BundleIdentity actual constructor(actual val documentId: String, actual val pdfResource: String?, actual val externalPdf: Boolean) {
    init { checkBundleIdentity(documentId, pdfResource, externalPdf) }
    fun bytes(): ByteArray = JSONObject().put("version", if (externalPdf) 2 else 1).put("documentId", documentId)
        .put("pdfResource", pdfResource ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
    fun write(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry(ENTRY)); zip.write(bytes()); zip.closeEntry()
    }
    actual companion object {
        actual const val ENTRY = "xnotes-identity.json"
        fun read(input: InputStream): BundleIdentity {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (out.size() <= 4096) {
                val n = input.read(buffer, 0, minOf(buffer.size, 4097 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            val bytes = out.toByteArray()
            require(bytes.size <= 4096) { "Document identity metadata is too large" }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("version") in 1..2) { "Unsupported document identity version" }
            return BundleIdentity(json.getString("documentId"), json.opt("pdfResource") as? String, json.getInt("version") == 2)
        }
        fun read(file: File): BundleIdentity? = ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name == ENTRY }.toList()
            require(entries.size <= 1) { "Duplicate identity metadata" }
            entries.singleOrNull()?.let { zip.getInputStream(it).use(::read) }
        }
    }
}
