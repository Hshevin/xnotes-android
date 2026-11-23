package com.xnotes.core.util


/** The forks an editor made, so a save still aimed at the file a document forked away from lands in its fork. */
class ForkLedger {
    private val lock = CoreLock()

    private class Forward(val to: String, val owner: CoreWeakReference<Any>)

    /** A fork's own name and the name its chain started from, both without the extension. */
    private class Named(val stem: String, val base: String)

    private val forwards = HashMap<String, Forward>()
    private val names = HashMap<String, Named>()

    /** Where [owner]'s save aimed at [uri] belongs: the newest fork [owner] made from it, else [uri] itself. */
    fun target(uri: String, owner: Any): String = lock.locked {
        var at = uri
        repeat(forwards.size) {
            val next = forwards[at]?.takeIf { it.owner.get() === owner } ?: return at
            at = next.to
        }
        return at
    }

    /** The name a fork of [uri] is numbered from: its chain's first name while [stem] is still the fork's own. */
    fun base(uri: String, stem: String): String = lock.locked { names[uri]?.takeIf { it.stem == stem }?.base ?: stem }

    /** Record that [owner] forked [from] into the new file [to], named [stem] and numbered from [base]. */
    fun record(from: String, to: String, owner: Any, stem: String, base: String) = lock.locked {
        forwards.remove(to) // a uri reused by this new file must not still forward elsewhere
        forwards[from] = Forward(to, CoreWeakReference(owner))
        names[to] = Named(stem, base)
    }
}
