package cn.wildfire.chat.kit.widget;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.lang.reflect.Field;

import cn.wildfire.chat.app.main.MainActivity;

/** Native regression: compare actual blurred samples with the page visible in a scrolled pager. */
public final class GlassBackdropInstrumentation extends Instrumentation {
    private Activity activity;
    private FrameLayout fixture;
    private ViewPager pager;
    private GlassBackdropRenderer renderer;
    private final int[] colors = {Color.RED, Color.GREEN, Color.BLUE};
    private Field displayedField;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_OK;
        try {
            displayedField = GlassBackdropRenderer.class.getDeclaredField("displayed");
            displayedField.setAccessible(true);
            Intent intent = new Intent(getTargetContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            runOnMainSync(this::createFixture);
            waitForIdleSync();
            runOnMainSync(() -> {
                if (pager.getWidth() == 0) throw new AssertionError("Fixture was not laid out");
                renderer.start();
            });
            for (int page : new int[]{0, 1, 2, 0, 2, 1}) {
                runOnMainSync(() -> {
                    pager.setCurrentItem(page, false);
                    renderer.requestRefresh();
                });
                assertSampleColor(page);
            }
            result.putString("stream", "\nPASS: blurred samples match all six selected pages\n");

        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + error + "\n");
            status = Activity.RESULT_CANCELED;
        } finally {
            if (activity != null) runOnMainSync(() -> {
                if (renderer != null) renderer.close();
                if (fixture != null && fixture.getParent() instanceof ViewGroup) {
                    ((ViewGroup) fixture.getParent()).removeView(fixture);
                }
            });
        }
        finish(status, result);
    }

    private void createFixture() {
        fixture = new FrameLayout(activity);
        pager = new ViewPager(activity);
        pager.setId(View.generateViewId());
        pager.setOffscreenPageLimit(2);
        pager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return colors.length; }
            @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }
            @NonNull @Override public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View page = new View(activity);
                page.setBackgroundColor(colors[position]);
                container.addView(page);
                return page;
            }
            @Override public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        });
        fixture.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        GlassTabBar bar = new GlassTabBar(activity, null);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(300), dp(62), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(8);
        fixture.addView(bar, params);
        renderer = new GlassBackdropRenderer(pager, bar);
        activity.addContentView(fixture, new ViewGroup.LayoutParams(-1, -1));
    }

    private void assertSampleColor(int page) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 2500;
        final int[] actual = {Color.TRANSPARENT};
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> {
                try {
                    Bitmap sample = (Bitmap) displayedField.get(renderer);
                    if (sample != null) actual[0] = sample.getPixel(sample.getWidth() / 2, sample.getHeight() / 2);
                } catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            if (actual[0] == colors[page]) return;
            SystemClock.sleep(30);
        }
        final int[] scrollX = new int[1];
        runOnMainSync(() -> scrollX[0] = pager.getScrollX());
        throw new AssertionError("page=" + page + ", scrollX=" + scrollX[0]
                + ", expected=" + Integer.toHexString(colors[page]) + ", sample=" + Integer.toHexString(actual[0]));
    }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
}
