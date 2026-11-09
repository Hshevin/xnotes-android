package com.xnotes.mindmap

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Validated immutable preview PNG, restricted to our static 8-bit encoder profile.
 * Neither callers nor history receive its mutable decoding buffer. This is not a PNG importer.
 */
class MapPreviewAsset private constructor(
    val key:String,val pngBase64:String,val width:Int,val height:Int,val byteSize:Int,
) {
    fun pngBytes():ByteArray=Base64.getDecoder().decode(pngBase64)
    override fun equals(other:Any?)=other is MapPreviewAsset && key==other.key && pngBase64==other.pngBase64 && width==other.width && height==other.height
    override fun hashCode()=key.hashCode()
    companion object {
        const val MAX_SIDE=512
        const val MAX_BYTES=256*1024
        const val MAX_ASSETS=1024
        const val MAX_TOTAL_BYTES=8*1024*1024
        const val MAX_TOTAL_PIXELS=16*1024*1024
        private const val MAX_BASE64=4*((MAX_BYTES+2)/3)
        fun fromPng(bytes:ByteArray):MapPreviewAsset {
            require(bytes.size in 57..MAX_BYTES) {"Preview PNG size limit"}
            val owned=bytes.copyOf()
            val size=inspect(owned)
            return MapPreviewAsset(hash(owned),Base64.getEncoder().encodeToString(owned),size.first,size.second,owned.size)
        }
        fun fromEncoded(key:String,pngBase64:String,width:Int,height:Int):MapPreviewAsset {
            require(key.matches(Regex("[0-9a-f]{64}")) && pngBase64.length<=MAX_BASE64)
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE)
            val bytes=Base64.getDecoder().decode(pngBase64)
            require(bytes.size in 57..MAX_BYTES && Base64.getEncoder().encodeToString(bytes)==pngBase64)
            require(hash(bytes)==key) {"Preview PNG digest mismatch"}
            val actual=inspect(bytes)
            require(actual.first==width && actual.second==height) {"Preview PNG dimensions mismatch"}
            return MapPreviewAsset(key,pngBase64,width,height,bytes.size)
        }
        private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        private fun int(bytes:ByteArray,at:Int):Long {
            require(at>=0 && at+4<=bytes.size)
            return (0..3).fold(0L) {n,i->(n shl 8) or (bytes[at+i].toLong() and 255)}
        }
        private fun inspect(bytes:ByteArray):Pair<Int,Int> {
            require(bytes.take(8)==listOf(137.toByte(),80.toByte(),78.toByte(),71.toByte(),13.toByte(),10.toByte(),26.toByte(),10.toByte()))
            var at=8;var width=0;var height=0;var channels=0;var colorType=-1;var data=false;var ended=false
            val optional=hashSetOf<String>()
            val compressed=ByteArrayOutputStream()
            while(at<bytes.size) {
                require(bytes.size-at>=12)
                val length=int(bytes,at);require(length<=bytes.size-at-12L)
                val n=length.toInt();val type=bytes.copyOfRange(at+4,at+8).toString(Charsets.US_ASCII)
                require(type.all {it in 'A'..'Z' || it in 'a'..'z'})
                require(type[2] in 'A'..'Z') {"Invalid PNG reserved chunk bit"}
                val crc=CRC32().apply {update(bytes,at+4,n+4)}
                require(crc.value==int(bytes,at+8+n)) {"Preview PNG CRC mismatch"}
                when(type) {
                    "IHDR" -> {
                        require(at==8 && n==13)
                        val w=int(bytes,at+8);val h=int(bytes,at+12)
                        require(w in 1..MAX_SIDE.toLong() && h in 1..MAX_SIDE.toLong())
                        width=w.toInt();height=h.toInt()
                        require(bytes[at+16].toInt()==8 && bytes[at+18].toInt()==0 && bytes[at+19].toInt()==0 && bytes[at+20].toInt()==0)
                        colorType=bytes[at+17].toInt()
                        channels=when(colorType) {0->1;2->3;4->2;6->4;else->throw IllegalArgumentException("Unsupported preview PNG color type")}
                    }
                    "IDAT" -> {require(width>0);data=true;compressed.write(bytes,at+8,n)}
                    "IEND" -> {require(width>0 && data && n==0 && at+12==bytes.size);ended=true}
                    // Keep decoder metadata small and uncompressed. Unknown/animated/profile/text
                    // chunks do not belong to a source preview and never reach BitmapFactory.
                    "PLTE","tRNS","sBIT","sRGB","gAMA","cHRM","pHYs" -> {
                        require(width>0 && !data && optional.add(type))
                        when(type) {
                            "PLTE" -> require(colorType in listOf(2,6) && "tRNS" !in optional && n in 3..768 && n%3==0)
                            "tRNS" -> {
                                require(colorType in listOf(0,2) && n==if(colorType==0) 2 else 6)
                                for(i in 0 until n step 2) require(bytes[at+8+i].toInt()==0)
                            }
                            "sBIT" -> {require("PLTE" !in optional && n==channels);require((0 until n).all {bytes[at+8+it].toInt() in 1..8})}
                            "sRGB" -> require("PLTE" !in optional && n==1 && bytes[at+8].toInt() in 0..3)
                            "gAMA" -> require("PLTE" !in optional && n==4 && int(bytes,at+8)>0)
                            "cHRM" -> require("PLTE" !in optional && n==32)
                            "pHYs" -> require(n==9 && bytes[at+16].toInt() in 0..1)
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported preview PNG chunk")
                }
                at+=n+12
            }
            require(ended && width>0 && data)
            val row=width*channels+1;val expected=row*height
            val inflater=Inflater()
            try {
                inflater.setInput(compressed.toByteArray())
                val buffer=ByteArray(8192);var total=0
                while(!inflater.finished()) {
                    val count=try {inflater.inflate(buffer)} catch(error:java.util.zip.DataFormatException) {throw IllegalArgumentException("Invalid preview PNG data",error)}
                    require(count>0 || inflater.finished()) {"Incomplete preview PNG data"}
                    require(total+count<=expected) {"Preview PNG inflated size mismatch"}
                    for(i in 0 until count) if((total+i)%row==0) require(buffer[i].toInt() in 0..4) {"Invalid preview PNG row filter"}
                    total+=count
                }
                require(total==expected && inflater.remaining==0) {"Preview PNG inflated size mismatch"}
            } finally {inflater.end()}
            return width to height
        }
    }
}
