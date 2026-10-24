package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.GradientDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V35HelpersTest {

    @Test
    public void httpIo_readRespectsMax() throws Exception {
        byte[] got = HttpIo.read(new ByteArrayInputStream("hello".getBytes("UTF-8")), 64);
        assertEquals("hello", new String(got, "UTF-8"));
    }

    @Test(expected = java.io.IOException.class)
    public void httpIo_readRejectsOversize() throws Exception {
        HttpIo.read(new ByteArrayInputStream(new byte[32]), 8);
    }

    @Test
    public void focusGlow_isOvalRadial() {
        GradientDrawable g = FocusGlow.drawable(0xFFE50914, 80f);
        assertEquals(GradientDrawable.OVAL, g.getShape());
        assertEquals(GradientDrawable.RADIAL_GRADIENT, g.getGradientType());
        assertNotNull(FocusGlow.halo(0xFFE50914, 80f));
    }

    @Test
    public void looks_glowTimingsMatchAllAppsAliases() {
        assertEquals(Looks.GLOW_IN_MS, AllAppsActivity.GLOW_IN_MS);
        assertEquals(Looks.GLOW_OUT_MS, AllAppsActivity.GLOW_OUT_MS);
        assertEquals(Looks.ALL_APPS_SCRIM, AllAppsActivity.WALLPAPER_SCRIM);
        assertTrue(Looks.GLOW_IN_MS >= 400 && Looks.GLOW_OUT_MS >= 200);
        assertEquals("dock", Looks.PREFS);
    }
}
