package com.xnotes.core

import com.xnotes.core.model.PageReferenceScope
import java.util.UUID
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun PageReferenceScope.Companion.uuidV5(namespace: UUID, name: String) =
    uuidV5(Uuid.parse(namespace.toString()), name)
