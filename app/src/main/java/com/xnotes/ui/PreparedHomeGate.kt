package com.xnotes.ui

/** A prepared operation's last business check calls this once, immediately before mutation. */
internal class PreparedHomeGate private constructor(private val attempt:LastPresentationAttempt,private val onCommitted:()->Unit) {
    fun commit(current:()->Boolean):Boolean {
        if(attempt.commit(current)!=LastPresentationWrite.Written) return false
        onCommitted();return true
    }
    suspend fun close()=attempt.close()

    companion object {
        suspend fun prepare(store:LastPresentationStore,ticket:LastPresentationStore.Ticket,onCommitted:()->Unit={}):PreparedHomeGate? {
            val attempt=store.begin(ticket,LastPresentation.Home)
            var held=false
            try {
                if(attempt.prepare()!=LastPresentationPrepare.Ready) return null
                held=true;return PreparedHomeGate(attempt,onCommitted)
            } finally {if(!held) attempt.close()}
        }
    }
}
