package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class ColdPresentationProblem { INVALID,UNAVAILABLE }

/** Retained routing facts only. An attempt never retains an Activity, Editor or callback. */
internal class ColdPresentationBootstrap {
    internal class Host internal constructor(internal val token:Any,val retained:Boolean)
    private var token=Any()
    var completed=false;private set
    var observation:LastPresentationObservation?=null;private set
    var problem by mutableStateOf<ColdPresentationProblem?>(null);private set
    var presentation:LastPresentation?=null;private set
    var restoredVersion by mutableStateOf(0L);private set
    var permitsLegacyMap=false;private set

    fun bind(retry:Boolean=false):Host {
        token=Any()
        return Host(token,completed && !retry)
    }
    fun current(host:Host)=token===host.token
    fun observed(host:Host,value:LastPresentationObservation):Boolean {
        if(!current(host)) return false
        observation=value;permitsLegacyMap=value.read===LastPresentationRead.Absent
        return true
    }
    fun finish(host:Host,value:LastPresentation?,failure:ColdPresentationProblem?=null):Boolean {
        if(!current(host)) return false
        completed=true;presentation=value;problem=failure
        if(failure==null) restoredVersion=Math.incrementExact(restoredVersion)
        return true
    }
    /** Accepted user intent retires preparation immediately, before its eventual adoption. */
    fun retire() {token=Any();completed=true;permitsLegacyMap=false;if(problem!=ColdPresentationProblem.INVALID) problem=null}
    fun presented(value:LastPresentation) {completed=true;presentation=value;problem=null}
    fun clearProblem() {problem=null}
}

/** Front-door feedback; this does not grant a departure or bypass its final storage checks. */
internal fun presentationEntryIssue(problem:ColdPresentationProblem?,mapVisible:Boolean=false):Int?=when {
    problem==ColdPresentationProblem.INVALID->com.xnotes.R.string.last_presentation_open_blocked
    mapVisible->com.xnotes.R.string.global_library_map_unsupported
    else->null
}
