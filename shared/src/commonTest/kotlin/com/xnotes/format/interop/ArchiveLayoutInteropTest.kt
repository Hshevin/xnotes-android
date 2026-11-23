package com.xnotes.format.interop

import com.xnotes.format.bundleReader
import okio.Buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveLayoutInteropTest {
    @Test fun reencodingPreservesPhysicalEntryOrderAndCompressionMethods() {
        fun layout(bytes: ByteArray) = bundleReader(Buffer().write(bytes)).use { reader ->
            buildList {
                while (true) {
                    val entry = reader.next() ?: break
                    add(entry.name to entry.method)
                    entry.finish()
                }
            }
        }
        for (name in interopFixtureNames().filter { it.endsWith(".xnote") || it.endsWith(".xcanvas") }) {
            val original = interopFixture(name)
            assertEquals(layout(original),layout(BundleInterop.reencode(name,original)),name)
        }
    }
}
