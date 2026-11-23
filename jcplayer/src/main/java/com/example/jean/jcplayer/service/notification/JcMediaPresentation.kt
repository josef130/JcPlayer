package com.example.jean.jcplayer.service.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.example.jean.jcplayer.JcArtworkProvider
import com.example.jean.jcplayer.model.JcAudio
import androidx.appcompat.content.res.AppCompatResources
import com.example.jean.jcplayer.R
import com.example.jean.jcplayer.general.PlayerUtil

/** One bounded, shared artwork bitmap; never decode application icons on progress ticks. */
internal object JcMediaPresentation {
    private const val ARTWORK_SIZE = 256
    private var cachedArtwork: Bitmap? = null

    private var provider: JcArtworkProvider? = null
    private var selectedKey: Pair<Any?, String>? = null
    private var selectedArtwork: Bitmap? = null

    @Synchronized fun setProvider(value: JcArtworkProvider?) {
        provider = value
        selectedKey = null
        selectedArtwork = null
    }

    @Synchronized fun artwork(context: Context, audio: JcAudio?): Bitmap {
        val source = provider ?: return artwork(context)
        if (audio == null) return artwork(context)
        val key = Pair<Any?, String>(audio.origin, audio.path)
        if (selectedKey == key) return selectedArtwork ?: artwork(context)
        val supplied = try { source.getArtwork(context.applicationContext, audio) }
            catch (error: Exception) { null }
        val result = if (supplied == null || supplied.isRecycled) artwork(context)
        else if (supplied.width > ARTWORK_SIZE || supplied.height > ARTWORK_SIZE) {
            val scale = ARTWORK_SIZE.toFloat() / maxOf(supplied.width, supplied.height)
            Bitmap.createScaledBitmap(supplied, maxOf(1, (supplied.width * scale).toInt()),
                maxOf(1, (supplied.height * scale).toInt()), true)
        } else supplied
        selectedKey = key
        selectedArtwork = result
        return result
    }

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
