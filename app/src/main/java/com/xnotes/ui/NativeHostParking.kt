package com.xnotes.ui

import androidx.annotation.MainThread

/** One opaque, read-only Main-thread observation. It grants no save, discard or model adoption. */
internal class NativeHostParking private constructor(private val observed:()->Boolean) {
    @MainThread fun current():Boolean=observed()
    companion object {
        internal fun observe(current:()->Boolean)=NativeHostParking(current)
    }
}
