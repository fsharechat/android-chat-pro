package cn.wildfire.chat.kit.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 文件下载水波纹进度：半透明水面从底部随进度上涨，波面正弦波动，
 * 圆角裁剪与文件类型色块一致。进度 0~100，100 或不可见时自动停止动画。
 */
public class WaveProgressView extends View {
    private static final int WAVE_COLOR = 0x59FFFFFF;
    private static final long WAVE_PERIOD_MS = 1200;

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();

    private float progress;
    private float phase;
    private float cornerRadius;
    private ValueAnimator phaseAnimator;

    public WaveProgressView(Context context) {
        this(context, null);
    }

    public WaveProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setColor(WAVE_COLOR);
        wavePaint.setStyle(Paint.Style.FILL);
        cornerRadius = 12 * context.getResources().getDisplayMetrics().density;
    }

    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
        updateAnimator();
        invalidate();
    }

    private void updateAnimator() {
        boolean shouldRun = isAttachedToWindow() && getVisibility() == VISIBLE
                && progress > 0 && progress < 100;
        if (shouldRun) {
            if (phaseAnimator == null) {
                phaseAnimator = ValueAnimator.ofFloat(0, (float) (2 * Math.PI));
                phaseAnimator.setDuration(WAVE_PERIOD_MS);
                phaseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                phaseAnimator.setInterpolator(new LinearInterpolator());
                phaseAnimator.addUpdateListener(animation -> {
                    phase = (float) animation.getAnimatedValue();
                    invalidate();
                });
            }
            if (!phaseAnimator.isRunning()) {
                phaseAnimator.start();
            }
        } else if (phaseAnimator != null && phaseAnimator.isRunning()) {
            phaseAnimator.cancel();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (phaseAnimator != null) {
            phaseAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimator();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        clipRect.set(0, 0, width, height);
        clipPath.reset();
        clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW);

        float level = height * (1 - progress / 100f);
        float amplitude = progress >= 100 ? 0 : height * 0.04f;

        wavePath.reset();
        wavePath.moveTo(0, height);
        wavePath.lineTo(0, waveY(0, width, level, amplitude));
        int step = Math.max(1, width / 24);
        for (int x = step; x <= width; x += step) {
            wavePath.lineTo(x, waveY(x, width, level, amplitude));
        }
        wavePath.lineTo(width, waveY(width, width, level, amplitude));
        wavePath.lineTo(width, height);
        wavePath.close();

        int save = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawPath(wavePath, wavePaint);
        canvas.restoreToCount(save);
    }

    private float waveY(float x, float width, float level, float amplitude) {
        return (float) (level + amplitude * Math.sin(2 * Math.PI * x / width + phase));
    }
}
