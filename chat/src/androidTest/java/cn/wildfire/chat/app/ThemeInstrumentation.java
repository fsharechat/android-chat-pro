package cn.wildfire.chat.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import cn.wildfire.chat.kit.setting.ThemeSettingActivity;

/** Exercises forced theme -> activity restart -> follow system -> activity restart. */
public final class ThemeInstrumentation extends Instrumentation {
    private boolean systemDark;
    private volatile Activity activity;
    private int checks;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        systemDark = Boolean.parseBoolean(args.getString("systemDark"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_OK;
        ThemeManager manager = ThemeManager.getInstance(getTargetContext());
        int originalMode = manager.getThemeMode();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                int forcedMode = systemDark ? ThemeManager.THEME_MODE_LIGHT : ThemeManager.THEME_MODE_DARK;
                runOnMainSync(() -> manager.setThemeMode(forcedMode));
                restartActivity();
                check(isDark() != systemDark, "manual mode after restart");
                runOnMainSync(() -> manager.setThemeMode(ThemeManager.THEME_MODE_SYSTEM));
                restartActivity();
                check(manager.getThemeMode() == ThemeManager.THEME_MODE_SYSTEM, "follow-system selection persisted");
                check(isDark() == systemDark, "follow system after restart: expected dark=" + systemDark + " actual=" + isDark());
            }
            changeSystemNight(!systemDark);
            check(waitForTheme(!systemDark), "follow-system updates an open page when system changes");
            check(manager.isDarkMode(activity) == !systemDark, "theme query reads system configuration");
            int manualMode = systemDark ? ThemeManager.THEME_MODE_DARK : ThemeManager.THEME_MODE_LIGHT;
            runOnMainSync(() -> manager.setThemeMode(manualMode));
            restartActivity();
            check(isDark() == systemDark, "manual theme applied before system change");
            changeSystemNight(systemDark);
            check(waitForTheme(systemDark), "system change preserves manual selection");
            check(manager.getThemeMode() == manualMode, "system change preserves saved preference");
            result.putString("stream", "\nPASS: " + checks + " theme checks; systemDark=" + systemDark + "\n");
        } catch (Throwable error) {
            status = Activity.RESULT_CANCELED;
            result.putString("stream", "\nFAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(error) + "\n");
        } finally {
            changeSystemNight(systemDark);
            runOnMainSync(() -> manager.setThemeMode(originalMode));
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
        finish(status, result);
    }

    @Override public void callActivityOnResume(Activity resumed) {
        super.callActivityOnResume(resumed);
        if (resumed instanceof ThemeSettingActivity) activity = resumed;
    }

    private void changeSystemNight(boolean dark) {
        try (android.os.ParcelFileDescriptor output = getUiAutomation().executeShellCommand(
                "cmd uimode night " + (dark ? "yes" : "no"));
             java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[128];
            while (stream.read(buffer) != -1) { /* wait for shell completion */ }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        android.os.SystemClock.sleep(300);
        waitForIdleSync();
    }

    private boolean waitForTheme(boolean dark) {
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        do {
            waitForIdleSync();
            if (isDark() == dark) return true;
            android.os.SystemClock.sleep(50);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private void restartActivity() {
        if (activity != null) { runOnMainSync(() -> activity.finish()); waitForIdleSync(); }
        activity = startActivitySync(new Intent(getTargetContext(), ThemeSettingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
    }
    private boolean isDark() {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
