package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/**
 * v27: the dock's scroller. Its padding is room for the focus scale and glow (drawn into, clipToPadding off), so
 * the fading edges are moved out to the view's real bounds; the stock ones start inside the padding and leave a
 * hard cut at the edge. The fade only shows on a side with more apps beyond it (HorizontalScrollView's strengths).
 */
public class FadeScrollView extends HorizontalScrollView {

    public FadeScrollView(Context context) {
        super(context);
    }

    public FadeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private boolean clipSelf;

    /**
     * Clip the row to this view's own (padded) bounds. clipChildren would clip only the wide inner row to itself, and
     * a clipping parent cuts the padding off (the scroller sits in it with negative margins).
     */
    void setClipSelf(boolean on) {
        if (clipSelf != on) {
            clipSelf = on;
            invalidate();
        }
    }

    boolean clipsSelf() {
        return clipSelf;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (clipSelf) {
            canvas.save();
            canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
            super.dispatchDraw(canvas);
            canvas.restore();
        } else {
            super.dispatchDraw(canvas);
        }
    }

    @Override
    protected boolean isPaddingOffsetRequired() {
        return true;
    }

    @Override
    protected int getLeftPaddingOffset() {
        return -getPaddingLeft();
    }

    @Override
    protected int getRightPaddingOffset() {
        return getPaddingRight();
    }

    @Override
    protected int getTopPaddingOffset() {
        return -getPaddingTop();
    }

    @Override
    protected int getBottomPaddingOffset() {
        return getPaddingBottom();
    }

    /**
     * Keeps a focused tile clear of the end fades, and at either end scrolls all the way so the tile has the padding
     * (its glow room) beside it. The stock version stops with the last tile flush against the view's edge.
     */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) return 0;
        int width = getWidth(), x = getScrollX();
        int margin = isHorizontalFadingEdgeEnabled() ? getHorizontalFadingEdgeLength() : getPaddingLeft();
        int max = Math.max(0, getChildAt(0).getWidth() + getPaddingLeft() + getPaddingRight() - width);
        int target = x;
        if (rect.right + margin > x + width) target = rect.right + margin - width;
        else if (rect.left - margin < x) target = rect.left - margin;
        target = Math.max(0, Math.min(max, target));
        return target - x;
    }

    /** Test hooks: how strongly each end is faded right now (0 = not at all). */
    float leftFade() {
        return isHorizontalFadingEdgeEnabled() ? getLeftFadingEdgeStrength() : 0f;
    }

    float rightFade() {
        return isHorizontalFadingEdgeEnabled() ? getRightFadingEdgeStrength() : 0f;
    }
}
