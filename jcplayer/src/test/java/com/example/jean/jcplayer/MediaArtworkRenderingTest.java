package com.example.jean.jcplayer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.appcompat.content.res.AppCompatResources;
import com.example.jean.jcplayer.service.notification.JcMediaPresentation;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MediaArtworkRenderingTest {
    @Test public void whiteNoteOccupiesMostOfArtworkHeightAndIsCached() throws Exception {
        Bitmap artwork = JcMediaPresentation.INSTANCE.artwork(RuntimeEnvironment.getApplication());
        assertEquals(256, artwork.getWidth());
        int whitePixels = 0, minY = 256, maxY = -1;
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            if (artwork.getPixel(x, y) == Color.WHITE) {
                whitePixels++; minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        assertTrue("White note should be clearly visible", whitePixels > 256 * 256 / 10);
        assertTrue("Note should occupy at least two thirds of artwork height", maxY - minY >= 170);
        assertSame(artwork, JcMediaPresentation.INSTANCE.artwork(RuntimeEnvironment.getApplication()));
        File preview = new File("build/reports/media-presentation/artwork.png");
        preview.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(preview)) {
            assertTrue(artwork.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    @Test public void transportGlyphsRetainPaddingInsideSystemButtonTargets() {
        for (int id : new int[]{R.drawable.jc_media_previous, R.drawable.jc_media_play,
                R.drawable.jc_media_pause, R.drawable.jc_media_next}) {
            Drawable drawable = AppCompatResources.getDrawable(RuntimeEnvironment.getApplication(), id);
            assertNotNull(drawable);
            Bitmap image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, 96, 96);
            drawable.draw(new Canvas(image));
            int minY = 96, maxY = -1;
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
                if (Color.alpha(image.getPixel(x, y)) > 0) {
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            assertTrue("Transport icon should not fill the entire button", minY >= 16 && maxY <= 79);
            assertTrue("Transport icon should remain visible", maxY - minY >= 40);
        }
    }
}