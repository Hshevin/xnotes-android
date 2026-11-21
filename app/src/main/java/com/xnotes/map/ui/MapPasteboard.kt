package com.xnotes.map.ui

import com.xnotes.map.MapFragment

/**
 * The copied mind map subtree, shared by every canvas in the app like the item clipboard. Copying
 * items clears it and copying a subtree clears the item clipboard, so a paste always lands the
 * last thing copied.
 */
object MapPasteboard {
    @Volatile var fragment: MapFragment? = null
}
