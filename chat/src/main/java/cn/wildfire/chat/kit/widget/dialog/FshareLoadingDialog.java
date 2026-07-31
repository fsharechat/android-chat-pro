package cn.wildfire.chat.kit.widget.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.wildfirechat.chat.R;

/**
 * 统一样式的加载弹窗：与 {@link FshareDialog} 同款 16dp 圆角卡片，
 * 内容为一行 spinner + 文案，没有标题也没有按钮。
 * 宽度按内容自适应，不像 FshareDialog 那样占屏宽 86%。
 */
public class FshareLoadingDialog extends Dialog {

    private final CharSequence message;

    /**
     * 创建并立即展示。
     *
     * @param message    加载文案，为空时只显示 spinner
     * @param cancelable 是否允许返回键 / 点击外部取消
     */
    public static FshareLoadingDialog show(@NonNull Context context,
                                           @Nullable CharSequence message,
                                           boolean cancelable) {
        FshareLoadingDialog dialog = new FshareLoadingDialog(context, message);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        dialog.show();
        return dialog;
    }

    private FshareLoadingDialog(@NonNull Context context, @Nullable CharSequence message) {
        super(context, R.style.FshareDialog);
        this.message = message;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_dialog_loading);

        TextView messageView = findViewById(R.id.loadingMessage);
        if (TextUtils.isEmpty(message)) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        }
    }
}
