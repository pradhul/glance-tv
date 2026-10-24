package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V31Test {
    @Test
    public void relativeBingUrlsBecomeAbsolute() {
        assertEquals("https://www.bing.com/th?id=OHR.X_EN-IN1", Bing.abs("/th?id=OHR.X_EN-IN1"));
        assertEquals("https://www.bing.com/th?id=A", Bing.abs("http://www.bing.com/th?id=A"));
        assertEquals("https://x.com/a", Bing.abs("//x.com/a"));
        assertEquals("https://www.bing.com/th?id=OHR.X_EN-IN1_1920x1080.jpg&w=64&h=36&c=1", Bing.thumbUrl("/th?id=OHR.X_EN-IN1"));
    }

    @Test
    public void manualPickOverridesWeatherOnlyInSameHalfOfDay() {
        MainActivity a = launch();
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("dock", Context.MODE_PRIVATE).edit()
                .putBoolean(Bing.KEY_MANUAL, true).putBoolean(Bing.KEY_MANUAL_NIGHT, false).commit();
        assertTrue(a.bingManualActive(false));
        assertTrue(!a.bingManualActive(true));
    }

    @Test
    public void refreshButtonStatesReturnToIdle() {
        MainActivity a = launch();
        a.setBingState(MainActivity.BING_LOADING);
        assertTrue(!a.bingRefresh.isEnabled());
        a.setBingState(MainActivity.BING_OK);
        assertEquals(MainActivity.BING_OK, a.bingState);
        assertTrue(a.bingRefresh.isEnabled());
        idle(1000);
        assertEquals(MainActivity.BING_OK, a.bingState);
        idle(1000);
        assertEquals(MainActivity.BING_IDLE, a.bingState);
        a.setBingState(MainActivity.BING_FAILED);
        idle(1500);
        assertEquals(MainActivity.BING_FAILED, a.bingState);
        idle(700);
        assertEquals(MainActivity.BING_IDLE, a.bingState);
    }

    private MainActivity launch() {
        Intent i = new Intent();
        i.setClassName("com.minimaltv.home", "com.minimaltv.home.MainActivity");
        MainActivity a = Robolectric.buildActivity(MainActivity.class, i).setup().get();
        idle(1500);
        return a;
    }

    private void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}
