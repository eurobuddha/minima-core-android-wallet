package com.eurobuddha.wallet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * Full-screen NFT viewer image: pinch to zoom (1x–6x), drag to pan (clamped to the image),
 * double-tap to toggle 1x/2.5x. Matrix-based on a plain ImageView — no dependencies.
 */
@SuppressLint("ClickableViewAccessibility")
public class ZoomImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix mMatrix = new Matrix();
    private float mScale = 1f;

    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;

    public ZoomImageView(Context zContext) {
        super(zContext);
        setScaleType(ScaleType.MATRIX);

        mScaleDetector = new ScaleGestureDetector(zContext, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        mGestureDetector = new GestureDetector(zContext, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                mMatrix.postTranslate(-dx, -dy);
                clamp();
                setImageMatrix(mMatrix);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (mScale > 1.01f) {
                    fitToView();
                } else {
                    scaleBy(DOUBLE_TAP_SCALE, e.getX(), e.getY());
                }
                return true;
            }
        });

        setOnTouchListener((v, ev) -> {
            mScaleDetector.onTouchEvent(ev);
            mGestureDetector.onTouchEvent(ev);
            return true;
        });
    }

    @Override
    public void setImageDrawable(Drawable zDrawable) {
        super.setImageDrawable(zDrawable);
        post(this::fitToView);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }

    /** Reset to fit-centre at 1x. */
    public void fitToView() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float fit = Math.min(getWidth() / dw, getHeight() / dh);
        mMatrix.reset();
        mMatrix.postScale(fit, fit);
        mMatrix.postTranslate((getWidth() - dw * fit) / 2f, (getHeight() - dh * fit) / 2f);
        mScale = 1f;
        setImageMatrix(mMatrix);
    }

    private void scaleBy(float zFactor, float zFx, float zFy) {
        float target = Math.max(MIN_SCALE, Math.min(MAX_SCALE, mScale * zFactor));
        float actual = target / mScale;
        mScale = target;
        mMatrix.postScale(actual, actual, zFx, zFy);
        clamp();
        setImageMatrix(mMatrix);
    }

    /** Keep the image covering the view: no empty gaps when zoomed, centred when smaller. */
    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        mMatrix.mapRect(rect);
        float dx = 0, dy = 0;
        if (rect.width() <= getWidth()) dx = (getWidth() - rect.width()) / 2f - rect.left;
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < getWidth()) dx = getWidth() - rect.right;
        if (rect.height() <= getHeight()) dy = (getHeight() - rect.height()) / 2f - rect.top;
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
        mMatrix.postTranslate(dx, dy);
    }
}
