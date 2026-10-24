package com.minimaltv.home;

import android.graphics.Bitmap;

/**
 * v30: a Gaussian blur for the Bing photo's variants, made once per download: three box passes per axis
 * (box sizes from the standard sigma-to-boxes formula), running sums, one int[] row buffer pair. No RenderScript.
 */
final class Blur {

    private Blur() {
    }

    /** A new RGB_565-free ARGB copy of src blurred with the given sigma (px). src is untouched. */
    static Bitmap gaussian(Bitmap src, float sigma) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        if (sigma >= 0.5f) {
            int[] b = new int[w * h];
            for (int box : boxes(sigma, 3)) {
                int r = (box - 1) / 2;
                pass(a, b, w, h, r);   // horizontal, a -> b transposed
                pass(b, a, h, w, r);   // vertical (on the transpose), b -> a back
            }
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    static int[] boxes(float sigma, int n) {
        double wIdeal = Math.sqrt((12 * sigma * sigma / n) + 1);
        int wl = (int) Math.floor(wIdeal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4.0 * wl - 4);
        long m = Math.round(mIdeal);
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) sizes[i] = i < m ? wl : wu;
        return sizes;
    }

    /** Box blur of each row of src (w x h) with radius r, written transposed into dst (h x w); edges clamp. */
    private static void pass(int[] src, int[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + Math.min(w - 1, Math.max(0, i))];
                sr += (c >> 16) & 255;
                sg += (c >> 8) & 255;
                sb += c & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = 0xFF000000 | (Math.round(sr * inv) << 16) | (Math.round(sg * inv) << 8) | Math.round(sb * inv);
                int in = src[row + Math.min(w - 1, x + r + 1)];
                int out = src[row + Math.max(0, x - r)];
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }
}
