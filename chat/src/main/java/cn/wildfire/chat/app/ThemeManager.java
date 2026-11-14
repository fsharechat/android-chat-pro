package cn.wildfire.chat.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.appcompat.app.AppCompatDelegate;

public class ThemeManager {
    public static final String PREF_NAME = "theme_pref";
    public static final String KEY_THEME_MODE = "theme_mode";
    
    public static final int THEME_MODE_LIGHT = 0;
    public static final int THEME_MODE_DARK = 1;
    public static final int THEME_MODE_SYSTEM = 2;
    
    private static ThemeManager instance;
    private SharedPreferences preferences;
    
    private ThemeManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public static ThemeManager getInstance(Context context) {
        if (instance == null) {
            instance = new ThemeManager(context.getApplicationContext());
        }
        return instance;
    }
    
    public void setThemeMode(int themeMode) {
        preferences.edit().putInt(KEY_THEME_MODE, themeMode).apply();
        applyThemeMode(themeMode);
    }
    
    public int getThemeMode() {
        return preferences.getInt(KEY_THEME_MODE, THEME_MODE_SYSTEM);
    }
    
    public void applyThemeMode(int themeMode) {
        switch (themeMode) {
            case THEME_MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_MODE_SYSTEM:
                // AppCompat 1.0 beta skips updating resources for FOLLOW_SYSTEM. Its previous
                // explicit mode can therefore survive an activity/task restart. Resolve from
                // framework system resources, never an activity's overridden configuration.
                applySystemNightMode(Resources.getSystem().getConfiguration());
                break;
        }
    }
    
    public void applyCurrentTheme() {
        applyThemeMode(getThemeMode());
    }

    public void onSystemConfigurationChanged(Configuration configuration) {
        if (getThemeMode() == THEME_MODE_SYSTEM) {
            applySystemNightMode(configuration);
        }
    }

    private void applySystemNightMode(Configuration configuration) {
        boolean dark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        AppCompatDelegate.setDefaultNightMode(dark
                ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }
    
    public boolean isDarkMode(Context context) {
        int themeMode = getThemeMode();
        if (themeMode == THEME_MODE_SYSTEM) {
            return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }
        return themeMode == THEME_MODE_DARK;
    }
    
    public String getThemeModeString(int themeMode) {
        switch (themeMode) {
            case THEME_MODE_LIGHT:
                return "浅色模式";
            case THEME_MODE_DARK:
                return "深色模式";
            case THEME_MODE_SYSTEM:
                return "跟随系统";
            default:
                return "跟随系统";
        }
    }
}
