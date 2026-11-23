package com.example.jean.jcplayer

import android.content.Context
import android.graphics.Bitmap
import com.example.jean.jcplayer.model.JcAudio

/** Optional media cover provider. Called on the main thread once per track change.
 * Return a small, ready bitmap or null for the default cover. Do not retain an Activity,
 * perform network/disk work here, or recycle returned bitmaps while playback uses them.
 */
fun interface JcArtworkProvider {
    fun getArtwork(context: Context, audio: JcAudio): Bitmap?
}
