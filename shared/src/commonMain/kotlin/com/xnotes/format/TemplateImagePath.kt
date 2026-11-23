package com.xnotes.format

fun validTemplateImagePath(src: String): Boolean =
    src.isNotEmpty() && !src.startsWith("/") && src.split('/').none { it == ".." }
