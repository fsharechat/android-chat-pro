package com.lqr.imagepicker.widget;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lqr.imagepicker.R;

/**
 * 与 chat 模块 FshareDialog 同款样式的确认弹窗，功能裁剪到只剩「标题 + 正文 + 两按钮」。
 *
 * <p>imagepicker 不依赖 chat（依赖方向是 chat → imagepicker），无法直接复用 FshareDialog，
 * 因此这里复制了一份最小实现。改弹窗样式时 chat 与 imagepicker 两边都要改。
 */
public class SimpleConfirmDialog extends Dialog {

    private static final float WIDTH_RATIO = 0.86f;

    private final CharSequence title;
    private final CharSequence content;
    private final CharSequence secondaryText;
    private final CharSequence primaryText;
    private final Runnable onPrimary;

    public static void show(@NonNull Context context,
                            @Nullable CharSequence title,
                            CharSequence content,
                            CharSequence secondaryText,
                            CharSequence primaryText,
                            @Nullable Runnable onPrimary) {
        new SimpleConfirmDialog(context, title, content, secondaryText, primaryText, onPrimary).show();
    }

    private SimpleConfirmDialog(@NonNull Context context,
                                @Nullable CharSequence title,
                                CharSequence content,
                                CharSequence secondaryText,
                                CharSequence primaryText,
                                @Nullable Runnable onPrimary) {
        super(context, R.style.SimpleConfirmDialog);
        this.title = title;
        this.content = content;
        this.secondaryText = secondaryText;
        this.primaryText = primaryText;
        this.onPrimary = onPrimary;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.simple_confirm_dialog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (getContext().getResources().getDisplayMetrics().widthPixels * WIDTH_RATIO),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView titleView = findViewById(R.id.dialogTitle);
        View titleDivider = findViewById(R.id.titleDivider);
        TextView contentView = findViewById(R.id.dialogContent);
        if (TextUtils.isEmpty(title)) {
            titleView.setVisibility(View.GONE);
            titleDivider.setVisibility(View.GONE);
            // 没有标题行撑顶部留白时，正文自己补上（布局里默认只留了 4dp）
            int padding = contentView.getPaddingLeft();
            contentView.setPadding(padding, padding, padding, padding);
        } else {
            titleView.setText(title);
            titleView.setVisibility(View.VISIBLE);
            titleDivider.setVisibility(View.VISIBLE);
        }
        contentView.setText(content);

        TextView secondary = findViewById(R.id.secondaryButton);
        secondary.setText(secondaryText);
        secondary.setOnClickListener(v -> dismiss());

        TextView primary = findViewById(R.id.primaryButton);
        primary.setText(primaryText);
        primary.setOnClickListener(v -> {
            if (onPrimary != null) {
                onPrimary.run();
            }
            dismiss();
        });
    }
}
