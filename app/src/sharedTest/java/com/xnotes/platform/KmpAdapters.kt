package com.xnotes.platform

import com.xnotes.format.XnotesPdf
import com.xnotes.format.inspect as inspectChannel
import com.xnotes.format.embedded as embeddedChannel
import com.xnotes.format.appendTail as appendChannel
import java.nio.channels.FileChannel

internal fun XnotesPdf.inspect(ch: FileChannel) = inspectChannel(ch)
internal fun XnotesPdf.embedded(ch: FileChannel, ours: XnotesPdf.Inspection.Ours) = embeddedChannel(ch, ours)
internal fun XnotesPdf.appendTail(ch: FileChannel, zip: FileChannel) = appendChannel(ch, zip)
