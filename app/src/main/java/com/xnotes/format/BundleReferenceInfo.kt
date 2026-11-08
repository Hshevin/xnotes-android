package com.xnotes.format

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Caller owns an immutable file. Inspects references without decoding ink or PDF. */
internal class BundleReferenceInfo private constructor(val header:BundleHeader,val orderedPageIds:List<String>,
                                                      val format:String?,val embeddedPdfHash:String?,val originalPdfHash:String?) {
    val pageIds:Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(orderedPageIds))
    companion object {
        fun legacy(header:BundleHeader):BundleReferenceInfo {
            require(header.anchors==null)
            return BundleReferenceInfo(header,emptyList(),null,null,null)
        }
        fun inspect(file:File,validatePayload:Boolean,cancelled:()->Boolean={false}):BundleReferenceInfo {
            val header=BundleHeader.read(file,cancelled)
            if(!validatePayload && header.anchors==null) return legacy(header)
            val payloads=BundleEntries.digests(file,cancelled,boundedManifest=header.anchors!=null)
            return ZipFile(file).use {zip->
                val entry=requireNotNull(zip.getEntry("manifest.json")) {"Missing native manifest"}
                val pages=if(header.anchors==null) emptyList() else {
                    val plan=AnchorManifest.inspect({zip.getInputStream(entry)},anchored=true,cancelled=cancelled)
                    require(plan.insertions.isEmpty()) {"Reference manifest needs explicit upgrade"}
                    plan.orderedPageIds
                }
                val metadata=zip.getInputStream(entry).use {input->
                    InputStreamReader(BundleEntries.cancellable(input,cancelled),Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).use {reader->
                        val p=JsonPull(reader,strict=header.anchors!=null);p.beginObject()
                        val names=hashSetOf<String>();var format:String?=null;var version=false;var hasPdf=false
                        while(p.hasNext()) {
                            check(!cancelled());val key=p.nextName()
                            require(names.add(key)) {"Duplicate native manifest field"}
                            when(key) {
                                "format" -> {require(p.peek()==JsonPull.Token.STRING);format=p.nextString()}
                                "has_pdf" -> {require(p.peek()==JsonPull.Token.BOOLEAN);hasPdf=p.nextBoolean()}
                                "version" -> {require(p.peek()==JsonPull.Token.NUMBER && p.nextNumberLiteral()=="1");version=true}
                                "pages" -> {
                                    require(p.peek()==JsonPull.Token.BEGIN_ARRAY);p.beginArray()
                                    while(p.hasNext()) {
                                        p.beginObject();val fields=hashSetOf<String>()
                                        while(p.hasNext()) {
                                            val field=p.nextName();require(fields.add(field)) {"Duplicate page field"}
                                            require(field!="reference_id" || header.anchors!=null) {"Page identities require their marker"}
                                            p.skipValue()
                                        }
                                        p.endObject()
                                    }
                                    p.endArray()
                                }
                                "items" -> {require(p.peek()==JsonPull.Token.BEGIN_ARRAY);p.skipValue()}
                                else -> p.skipValue()
                            }
                        }
                        p.endObject();require(p.peek()==JsonPull.Token.END_DOCUMENT)
                        require(version && (format=="xnote" && "pages" in names || format=="xcanvas" && "items" in names)) {
                            "Unsupported native manifest structure"
                        }
                        requireNotNull(format) to hasPdf
                    }
                }
                val format=metadata.first;val hasPdf=metadata.second
                val pdf=payloads[BundlePdf.ENTRY]
                require(!hasPdf || format=="xnote") {"Only a note can declare a PDF original"}
                require(hasPdf || pdf==null && header.identity?.externalPdf!=true) {"Undeclared PDF original"}
                require(!hasPdf || header.identity?.externalPdf==true || pdf!=null) {"Embedded PDF original is missing"}
                require(header.identity?.externalPdf!=true || pdf==null) {"External PDF bundle also embeds an original"}
                header.identity?.pdfResource?.let {id->if(pdf!=null) require(pdf==id) {"Embedded PDF differs from recorded original"}}
                BundleReferenceInfo(header,pages,format,pdf,if(hasPdf) {
                    if(header.identity?.externalPdf==true) requireNotNull(header.identity.pdfResource) else requireNotNull(pdf)
                } else null)
            }
        }
    }
}
