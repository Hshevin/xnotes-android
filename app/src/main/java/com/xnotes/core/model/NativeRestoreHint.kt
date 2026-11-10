package com.xnotes.core.model

import com.xnotes.core.util.DocumentKind

/** A hint for verifying an existing per-slot working session, never authority to save or reopen a file. */
internal data class NativeRestoreHint(val documentId:String,val path:String?,val kind:DocumentKind)
