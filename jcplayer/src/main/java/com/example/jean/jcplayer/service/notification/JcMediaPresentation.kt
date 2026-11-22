package com.example.jean.jcplayer.service.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import com.example.jean.jcplayer.R
import com.example.jean.jcplayer.general.PlayerUtil

/** One bounded, shared artwork bitmap; never decode application icons on progress ticks. */
internal object JcMediaPresentation {
    private const val ARTWORK_SIZE = 256
    private var cachedArtwork: Bitmap? = null

    @Synchronized fun artwork(context: Context): Bitmap {
        cachedArtwork?.let { return it }
        val bitmap = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.jc_media_artwork))
        drawable.setBounds(0, 0, ARTWORK_SIZE, ARTWORK_SIZE)
        drawable.draw(Canvas(bitmap))
        cachedArtwork = bitmap
        return bitmap
    }

    fun durationText(duration: Long): String = if (duration > 0)
        PlayerUtil.toTimeSongString(duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else "--:--"
}