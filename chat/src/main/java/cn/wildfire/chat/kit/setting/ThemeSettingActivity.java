package cn.wildfire.chat.kit.setting;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import butterknife.OnClick;
import cn.wildfire.chat.app.MyApp;
import cn.wildfire.chat.app.ThemeManager;
import cn.wildfire.chat.app.main.SplashActivity;
import cn.wildfire.chat.kit.WfcBaseActivity;
import cn.wildfirechat.chat.R;

public class ThemeSettingActivity extends WfcBaseActivity {

    @Override
    protected int contentLayout() {
        return R.layout.activity_theme_setting;
    }

    @Override
    protected void afterViews() {
        super.afterViews();
        setTitle("主题设置");
    }

    @OnClick(R.id.lightModeOptionItemView)
    void setLightMode() {
        showThemeChangeDialog(ThemeManager.THEME_MODE_LIGHT);
    }

    @OnClick(R.id.darkModeOptionItemView)
    void setDarkMode() {
        showThemeChangeDialog(ThemeManager.THEME_MODE_DARK);
    }

    @OnClick(R.id.systemModeOptionItemView)
    void setSystemMode() {
        showThemeChangeDialog(ThemeManager.THEME_MODE_SYSTEM);
    }

    private void showThemeChangeDialog(final int themeMode) {
        String currentMode = ThemeManager.getInstance(this).getThemeModeString(ThemeManager.getInstance(this).getThemeMode());
        String newMode = ThemeManager.getInstance(this).getThemeModeString(themeMode);
        
        new MaterialDialog.Builder(this)
                .title("切换主题")
                .content("当前主题：" + currentMode + "\n新主题：" + newMode + "\n\n切换主题需要重启应用，是否继续？")
                .positiveText("确定")
                .negativeText("取消")
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(MaterialDialog dialog, DialogAction which) {
                        ThemeManager.getInstance(ThemeSettingActivity.this).setThemeMode(themeMode);
                        Toast.makeText(ThemeSettingActivity.this, "主题已切换，应用将重启", Toast.LENGTH_SHORT).show();
                        
                        // 重启应用
                        Intent intent = new Intent(ThemeSettingActivity.this, SplashActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    }
                })
                .show();
    }
} 