package com.xnotes.storage

/** Installed identically in ignored benchmark archives only; never copied into production. */
internal object BenchmarkStorageLockTiming {
    @JvmField @Volatile @PublishedApi internal var observer:((String,Long,Long)->Unit)?=null
    @JvmField @Volatile @PublishedApi internal var entered:((String)->Unit)?=null
    inline fun <T> locked(label:String,lock:Any,block:()->T):T {
        // Nested operations share the actual monitor and count the outer hold exactly once.
        if(Thread.holdsLock(lock)) return synchronized(lock) {block()}
        val requested=System.nanoTime()
        return synchronized(lock) {
            val acquired=System.nanoTime()
            entered?.invoke(label)
            try {block()} finally {observer?.invoke(label,acquired-requested,System.nanoTime()-acquired)}
        }
    }
}
