package com.needlegroove.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class VinylView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator spin = ValueAnimator.ofFloat(0, 360);
    private final Path clip = new Path();
    private Bitmap artwork;
    private float angle;
    private int glow = Color.rgb(119, 73, 133);
    private boolean black;
    private final RectF labelBounds = new RectF(), sleeveBounds = new RectF();
    private RadialGradient glowShader;
    private LinearGradient sleeveShader;
    private final Typeface sleeveTypeface = Typeface.create("serif", Typeface.NORMAL);

    VinylView(Context context) {
        super(context);
        setContentDescription("Album sleeve beside a spinning vinyl record");
        spin.setDuration(5000);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(animation -> { angle = (float) animation.getAnimatedValue(); invalidate(); });
    }
    void setArtwork(Bitmap bitmap) {
        artwork = bitmap;
        if (bitmap != null) glow = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
        updateShaders();
        invalidate();
    }
    void setBlack(boolean value) { black = value; invalidate(); }
    void playing(boolean playing) {
        if (playing && ValueAnimator.areAnimatorsEnabled()) {
            if (!spin.isStarted()) spin.start(); else spin.resume();
        } else if (spin.isStarted()) spin.pause();
    }
    @Override protected void onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { updateShaders(); }
    private void updateShaders() {
        if (getWidth() == 0) return;
        float w = getWidth(), h = getHeight(), size = Math.min(w * .43f, h * .67f);
        float left = (w - size * 1.9f) / 2, top = (h - size) / 2;
        glowShader = new RadialGradient(w * .5f, h * .5f, w * .6f,
                new int[]{withAlpha(glow, 95), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
        sleeveShader = new LinearGradient(left, top, left + size, top + size,
                new int[]{0x22ffffff, 0x00000000, 0x22000000}, null, Shader.TileMode.CLAMP);
        sleeveBounds.set(left, top, left + size, top + size);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float size = Math.min(w * .43f, h * .67f), left = (w - size * 1.9f) / 2, top = (h - size) / 2;
        if (!black) {
            paint.setShader(glowShader);
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
        paint.setColor(0x44000000);
        canvas.drawOval(left - 10, top + size - 6, left + size * 2, top + size + 22, paint);
        float cx = left + size * 1.45f, cy = top + size * .5f, radius = size * .47f;
        canvas.save();
        canvas.rotate(angle, cx, cy);
        paint.setColor(0xff101014);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        for (float r = radius * .43f; r < radius; r += 3) {
            paint.setColor(0xff29262c);
            canvas.drawCircle(cx, cy, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff2bf6a);
        canvas.drawCircle(cx, cy, radius * .37f, paint);
        if (artwork != null) {
            clip.reset();
            clip.addCircle(cx, cy, radius * .37f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            labelBounds.set(cx - radius * .37f, cy - radius * .37f, cx + radius * .37f, cy + radius * .37f);
            canvas.drawBitmap(artwork, null, labelBounds, paint);
            canvas.restore();
        }
        paint.setColor(0xff111114);
        canvas.drawCircle(cx, cy, radius * .045f, paint);
        canvas.restore();
        paint.setColor(0xffeadecf);
        canvas.drawRoundRect(sleeveBounds, 4, 4, paint);
        if (artwork != null) canvas.drawBitmap(artwork, null, sleeveBounds, paint);
        else {
            paint.setColor(0xff806457);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(sleeveTypeface);
            paint.setTextSize(size * .09f);
            canvas.drawText("No artwork", left + size / 2, top + size * .53f, paint);
        }
        paint.setShader(sleeveShader);
        canvas.drawRect(sleeveBounds, paint);
        paint.setShader(null);
    }
    private static int withAlpha(int color, int alpha) { return color & 0x00ffffff | alpha << 24; }
}
