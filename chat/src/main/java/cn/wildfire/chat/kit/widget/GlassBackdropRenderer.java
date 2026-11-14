package cn.wildfire.chat.kit.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
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

    public interface Target {
        View backdropView();
        void setBackdrop(Bitmap bitmap, float padding);
        default void setScrollEffect(float shift, float activity) {}
    }

    private static final int DOWNSAMPLE = 8;
    private final View source;
    private final View bar;
    private final Target target;
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
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::onTreeScrolled;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::bindScrollSources;
    private final ArrayList<RecyclerView> observedLists = new ArrayList<>();
    private final ArrayList<ScrollBinding> observedScrollViews = new ArrayList<>();
    private final Rect sourceBounds = new Rect(), barBounds = new Rect();
    private final int touchSlop;
    private boolean touchCandidate, contentDragging;
    private float initialX, initialY, lastY, dragShift;
    private final Runnable settleSurface;
    private final RecyclerView.OnScrollListener listScrollListener = new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(RecyclerView view, int dx, int dy) {
            requestRefresh();
            onNativeScroll(dy);
        }
    };
    private final ViewTreeObserver.OnPreDrawListener drawListener;
    // Accessed exclusively by the single worker thread.
    private int[] pixels, scratch;

    public GlassBackdropRenderer(View source, Target target) {
        this.source = source;
        this.target = target;
        this.bar = target.backdropView();
        touchSlop = ViewConfiguration.get(source.getContext()).getScaledTouchSlop();
        settleSurface = () -> target.setScrollEffect(0, 0);
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
        observer.addOnGlobalLayoutListener(layoutListener);
        bindScrollSources();
        requestRefresh();
    }
    public void stop() {
        active = false;
        generation++;
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(drawListener);
            observer.removeOnScrollChangedListener(scrollListener);
            observer.removeOnGlobalLayoutListener(layoutListener);
        }
        for (RecyclerView list : observedLists) list.removeOnScrollListener(listScrollListener);
        observedLists.clear();
        observedScrollViews.clear();
        touchCandidate = contentDragging = false;
        observer = null;
        main.removeCallbacks(refresh);
        main.removeCallbacks(settleSurface);
    }
    public void close() {
        stop();
        closed = true;
        worker.shutdown();
        target.setBackdrop(null, 0);
        recycle(displayed);
        recycle(spare);
        displayed = spare = null;
    }
    public void requestRefresh() {
        dirty = true;
        if (active) source.postInvalidateOnAnimation();
    }
    /** Observes content gestures without taking them away from lists, the pager or buttons. */
    public void onContentTouchEvent(MotionEvent event) {
        if (!active) return;
        float x = event.getRawX(), y = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchCandidate = source.getGlobalVisibleRect(sourceBounds)
                        && sourceBounds.contains((int) x, (int) y)
                        && !(bar.getGlobalVisibleRect(barBounds) && barBounds.contains((int) x, (int) y));
                contentDragging = false;
                initialX = x; initialY = lastY = y; dragShift = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!touchCandidate) break;
                if (event.getPointerCount() != 1) {
                    releaseSurface();
                    break;
                }
                float horizontal = Math.abs(x - initialX), vertical = Math.abs(y - initialY);
                if (!contentDragging && horizontal > touchSlop && horizontal > vertical) {
                    releaseSurface();
                    break;
                }
                if (!contentDragging && vertical <= touchSlop) break;
                contentDragging = true;
                float dy = y - lastY;
                lastY = y;
                float density = source.getResources().getDisplayMetrics().density;
                dragShift = Math.max(-1, Math.min(1, dragShift + dy / (160 * density)));
                target.setScrollEffect(dragShift, Math.min(1, .35f + Math.abs(dy) / (24 * density)));
                main.removeCallbacks(settleSurface);
                requestRefresh();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                releaseSurface();
                break;
            default: break;
        }
    }
    private void releaseSurface() {
        touchCandidate = contentDragging = false;
        main.removeCallbacks(settleSurface);
        target.setScrollEffect(0, 0);
    }
    private void onNativeScroll(int dy) {
        if (!active || contentDragging || dy == 0) return;
        float amount = Math.min(1, Math.abs(dy) / (24 * source.getResources().getDisplayMetrics().density));
        target.setScrollEffect(Math.signum(dy) * amount, .35f + .65f * amount);
        main.removeCallbacks(settleSurface);
        main.postDelayed(settleSurface, 100);
    }
    private void onTreeScrolled() {
        requestRefresh();
        for (ScrollBinding binding : observedScrollViews) {
            int y = binding.view.getScrollY();
            onNativeScroll(y - binding.lastY);
            binding.lastY = y;
        }
    }
    private void bindScrollSources() { observeScrollSource(source); }
    private void observeScrollSource(View view) {
        if (view instanceof RecyclerView) {
            RecyclerView list = (RecyclerView) view;
            if (!observedLists.contains(list)) {
                list.addOnScrollListener(listScrollListener);
                observedLists.add(list);
            }
            // Recycled rows do not own the home page's scroll position.
            return;
        }
        if (view instanceof ScrollView) {
            boolean observed = false;
            for (ScrollBinding binding : observedScrollViews) if (binding.view == view) observed = true;
            if (!observed) observedScrollViews.add(new ScrollBinding((ScrollView) view));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) observeScrollSource(group.getChildAt(i));
        }
    }
    private static final class ScrollBinding {
        final ScrollView view;
        int lastY;
        ScrollBinding(ScrollView view) { this.view = view; lastY = view.getScrollY(); }
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
        // Direct View.draw(Canvas) does not apply the scroll translation normally supplied
        // by its parent. Without this, a ViewPager always samples its first page.
        canvas.translate(sourceLocation[0] - barLocation[0] + padding - source.getScrollX(),
                sourceLocation[1] - barLocation[1] + padding - source.getScrollY());
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
                    target.setBackdrop(displayed, padding);
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
