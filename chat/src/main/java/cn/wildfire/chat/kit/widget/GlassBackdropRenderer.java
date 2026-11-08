package cn.wildfire.chat.kit.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.wildfirechat.chat.R;

/** Samples only the content sibling, never the glass bar itself. All bitmap ownership is explicit. */
public final class GlassBackdropRenderer {
    /** Marks offscreen draws so participating views can skip stateful screen-only effects. */
    static final class CaptureCanvas extends Canvas {
        CaptureCanvas(Bitmap bitmap) { super(bitmap); }
    }

    private static final int DOWNSAMPLE = 8;
    private final View source;
    private final GlassTabBar bar;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final int[] sourceLocation = new int[2], barLocation = new int[2];
    private final float padding;
    private Bitmap displayed, spare;
    private boolean active, busy, dirty = true, closed;
    private long lastCapture;
    private long interval = 34;
    private int generation;
    private ViewTreeObserver observer;
    private final Runnable refresh;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::requestRefresh;
    private final ViewTreeObserver.OnPreDrawListener drawListener;
    // Accessed exclusively by the single worker thread.
    private int[] pixels, scratch;

    public GlassBackdropRenderer(View source, GlassTabBar bar) {
        this.source = source;
        this.bar = bar;
        refresh = () -> { if (active) source.invalidate(); };
        drawListener = () -> {
            if (active && (dirty || isDirty(source))) capture();
            return true;
        };
        padding = 16 * source.getResources().getDisplayMetrics().density;
    }
    public void start() {
        if (active || closed) return;
        active = true;
        observer = source.getViewTreeObserver();
        observer.addOnPreDrawListener(drawListener);
        observer.addOnScrollChangedListener(scrollListener);
        requestRefresh();
    }
    public void stop() {
        active = false;
        generation++;
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(drawListener);
            observer.removeOnScrollChangedListener(scrollListener);
        }
        observer = null;
        main.removeCallbacks(refresh);
    }
    public void close() {
        stop();
        closed = true;
        worker.shutdown();
        bar.setBackdrop(null, 0);
        recycle(displayed);
        recycle(spare);
        displayed = spare = null;
    }
    public void requestRefresh() {
        dirty = true;
        if (active) source.postInvalidateOnAnimation();
    }
    private boolean isDirty(View view) {
        if (view.isDirty()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && isDirty(child)) return true;
            }
        }
        return false;
    }
    private void capture() {
        dirty = true;
        if (busy || bar.getWidth() == 0 || bar.getHeight() == 0) return;
        long now = SystemClock.uptimeMillis();
        long delay = interval - (now - lastCapture);
        if (delay > 0) {
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, delay);
            return;
        }
        int width = (int) Math.ceil((bar.getWidth() + 2 * padding) / DOWNSAMPLE);
        int height = (int) Math.ceil((bar.getHeight() + 2 * padding) / DOWNSAMPLE);
        Bitmap buffer = spare;
        spare = null;
        if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
            recycle(buffer);
            buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        source.getLocationInWindow(sourceLocation);
        bar.getLocationInWindow(barLocation);
        buffer.eraseColor(source.getResources().getColor(R.color.paper));
        Canvas canvas = new CaptureCanvas(buffer);
        canvas.scale(width / (bar.getWidth() + 2 * padding), height / (bar.getHeight() + 2 * padding));
        canvas.translate(sourceLocation[0] - barLocation[0] + padding,
                sourceLocation[1] - barLocation[1] + padding);
        source.draw(canvas);
        lastCapture = now;
        dirty = false;
        busy = true;
        int requestGeneration = generation;
        Bitmap captured = buffer;
        worker.execute(() -> {
            blur(captured);
            long elapsed = SystemClock.uptimeMillis() - now;
            main.post(() -> {
                busy = false;
                if (closed) { recycle(captured); return; }
                // Lower refresh frequency on slower devices without affecting indicator animation.
                interval = elapsed > 24 ? 67 : 34;
                if (!active || generation != requestGeneration) {
                    spare = captured;
                } else {
                    spare = displayed;
                    displayed = captured;
                    bar.setBackdrop(displayed, padding);
                }
                if (dirty && active) source.postInvalidateOnAnimation();
            });
        });
    }
    private static void recycle(Bitmap bitmap) { if (bitmap != null) bitmap.recycle(); }

    /** Three separable box passes approximate a Gaussian on the small sampled region. */
    private void blur(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (pixels == null || pixels.length != width * height) {
            pixels = new int[width * height];
            scratch = new int[pixels.length];
        }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(pixels, scratch, width, height, true);
            boxPass(scratch, pixels, width, height, false);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }
    static void boxPass(int[] input, int[] output, int width, int height, boolean horizontal) {
        int length = horizontal ? width : height, lines = horizontal ? height : width;
        int radius = 2, diameter = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int red = 0, green = 0, blue = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int index = Math.max(0, Math.min(length - 1, offset));
                int c = input[horizontal ? line * width + index : index * width + line];
                red += (c >> 16) & 255; green += (c >> 8) & 255; blue += c & 255;
            }
            for (int pos = 0; pos < length; pos++) {
                output[horizontal ? line * width + pos : pos * width + line] = 0xff000000
                        | (red / diameter << 16) | (green / diameter << 8) | blue / diameter;
                int outgoing = Math.max(0, pos - radius), incoming = Math.min(length - 1, pos + radius + 1);
                int a = input[horizontal ? line * width + outgoing : outgoing * width + line];
                int b = input[horizontal ? line * width + incoming : incoming * width + line];
                red += ((b >> 16) & 255) - ((a >> 16) & 255);
                green += ((b >> 8) & 255) - ((a >> 8) & 255);
                blue += (b & 255) - (a & 255);
            }
        }
    }
}
