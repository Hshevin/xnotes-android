package com.xnotes.storage

import com.xnotes.format.AnchorMetadata
import com.xnotes.format.write as writeMetadata
import java.util.zip.ZipOutputStream

internal fun AnchorMetadata.write(out: ZipOutputStream) = writeMetadata(out)
