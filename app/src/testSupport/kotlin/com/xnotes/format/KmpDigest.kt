package com.xnotes.format

import java.io.InputStream
import okio.source

internal fun BundleMigration.digest(input: InputStream, cancelled: () -> Boolean) = digest(input.source(), cancelled)
