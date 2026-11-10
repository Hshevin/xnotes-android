package com.xnotes.mindmap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

/** Configuration recreation transfers this one host lease; final Activity closure releases it. */
internal class MapWorkspaceOwner(context:Context):ViewModel() {
    val bootstrap=com.xnotes.ui.ColdPresentationBootstrap()
    val workspace=MapWorkspace(context.applicationContext,viewModelScope)
    val networks=FileNetworkWorkspace(context.applicationContext,viewModelScope)
    val originals=OriginalPdfWorkspace(context.applicationContext,viewModelScope)
    val originalLibrary=OriginalPdfLibraryState(context.applicationContext,viewModelScope)
    override fun onCleared() {originalLibrary.dispose();originals.dispose();networks.dispose();workspace.dispose();super.onCleared()}
    class Factory(private val context:Context):ViewModelProvider.Factory {
        override fun <T:ViewModel> create(modelClass:Class<T>):T {
            require(modelClass==MapWorkspaceOwner::class.java)
            @Suppress("UNCHECKED_CAST") return MapWorkspaceOwner(context) as T
        }
    }
}
