package cn.wildfire.chat.kit.widget.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.wildfirechat.chat.R;

/**
 * 应用统一样式的居中卡片弹窗。样式基准是「当前连接服务」弹窗
 * ({@link cn.wildfire.chat.kit.net.ServerInfoDialog})：16dp 圆角 surface 卡片、
 * fog 分割线、底部等宽双按钮。
 *
 * <p>正文区支持三选一：{@link Builder#content}、{@link Builder#input}、
 * {@link Builder#customView}，同时设置时以最后一次调用为准。
 *
 * <p>按钮点击回调返回后自动 dismiss；只设置 primary 时它占满整行。
 */
public class FshareDialog extends Dialog {

    /** 弹窗宽度占屏宽的比例，与 ServerInfoDialog 保持一致。 */
    private static final float WIDTH_RATIO = 0.86f;

    public interface OnClickListener {
        void onClick(FshareDialog dialog);
    }

    /** 正文区三选一的模式标记。 */
    private enum ContentMode {
        NONE, TEXT, INPUT, CUSTOM
    }

    private final Builder builder;
    private EditText inputView;

    private FshareDialog(@NonNull Builder builder) {
        super(builder.context, R.style.FshareDialog);
        this.builder = builder;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_dialog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (getContext().getResources().getDisplayMetrics().widthPixels * WIDTH_RATIO),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (builder.contentMode == ContentMode.INPUT) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        }

        setCancelable(builder.cancelable);
        setCanceledOnTouchOutside(builder.cancelable);

        bindTitle();
        bindContent();
        bindButtons();
    }

    private void bindTitle() {
        TextView titleView = findViewById(R.id.dialogTitle);
        View titleDivider = findViewById(R.id.titleDivider);
        if (TextUtils.isEmpty(builder.title)) {
            titleView.setVisibility(View.GONE);
            titleDivider.setVisibility(View.GONE);
            return;
        }
        titleView.setText(builder.title);
        titleView.setVisibility(View.VISIBLE);
        titleDivider.setVisibility(View.VISIBLE);
    }

    private void bindContent() {
        FrameLayout container = findViewById(R.id.contentContainer);
        switch (builder.contentMode) {
            case TEXT:
                container.addView(createContentTextView());
                break;
            case INPUT:
                inputView = createInputView();
                container.addView(inputView);
                break;
            case CUSTOM: {
                // customView 可能被上一次 show 挂过 parent，先摘下来再挂
                ViewGroup parent = (ViewGroup) builder.customView.getParent();
                if (parent != null) {
                    parent.removeView(builder.customView);
                }
                container.addView(builder.customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                break;
            }
            case NONE:
            default:
                container.setVisibility(View.GONE);
                break;
        }
    }

    private TextView createContentTextView() {
        TextView view = new TextView(getContext());
        view.setText(builder.content);
        view.setTextSize(15);
        view.setLineSpacing(dp(4), 1f);
        view.setTextColor(getContext().getResources().getColor(R.color.ink));
        // 无标题时正文自己撑起顶部留白，有标题时标题行已经留过了
        int top = TextUtils.isEmpty(builder.title) ? dp(20) : dp(4);
        view.setPadding(dp(20), top, dp(20), dp(20));
        view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private EditText createInputView() {
        EditText view = new EditText(getContext());
        view.setHint(builder.inputHint);
        view.setText(builder.inputPrefill);
        view.setSingleLine(true);
        view.setTextSize(15);
        view.setTextColor(getContext().getResources().getColor(R.color.ink));
        view.setHintTextColor(getContext().getResources().getColor(R.color.mist));
        if (!TextUtils.isEmpty(builder.inputPrefill)) {
            view.setSelection(builder.inputPrefill.length());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), TextUtils.isEmpty(builder.title) ? dp(20) : dp(4),
                dp(20), dp(20));
        view.setLayoutParams(lp);
        view.requestFocus();
        return view;
    }

    private void bindButtons() {
        TextView secondary = findViewById(R.id.secondaryButton);
        TextView primary = findViewById(R.id.primaryButton);
        View buttonDivider = findViewById(R.id.buttonDivider);
        LinearLayout buttonBar = findViewById(R.id.buttonBar);

        boolean hasSecondary = !TextUtils.isEmpty(builder.secondaryText);
        boolean hasPrimary = !TextUtils.isEmpty(builder.primaryText);

        if (!hasSecondary && !hasPrimary) {
            buttonDivider.setVisibility(View.GONE);
            buttonBar.setVisibility(View.GONE);
            return;
        }

        if (hasSecondary) {
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(builder.secondaryText);
            secondary.setOnClickListener(v -> {
                if (builder.secondaryListener != null) {
                    builder.secondaryListener.onClick(FshareDialog.this);
                }
                dismiss();
            });
        }

        if (hasPrimary) {
            primary.setVisibility(View.VISIBLE);
            primary.setText(builder.primaryText);
            primary.setOnClickListener(v -> {
                if (builder.primaryListener != null) {
                    builder.primaryListener.onClick(FshareDialog.this);
                }
                dismiss();
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) primary.getLayoutParams();
            if (hasSecondary) {
                lp.setMarginStart(dp(12));
            } else {
                // 只有一个按钮时占满整行：真正起作用的是去掉左边距（setMarginStart(0)）；
                // bg_dialog_button_full 目前与 bg_dialog_button_primary 内容相同，
                // 单独保留这份资源是为了以后可以只调整单按钮样式而不影响双按钮场景
                lp.setMarginStart(0);
                primary.setBackgroundResource(R.drawable.bg_dialog_button_full);
            }
            primary.setLayoutParams(lp);
        }
    }

    /** 仅在用 {@link Builder#input} 构建时有意义，其余情况返回空串。 */
    @NonNull
    public String getInputText() {
        return inputView == null ? "" : inputView.getText().toString();
    }

    private int dp(int value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    public static class Builder {
        private final Context context;

        private CharSequence title;
        private CharSequence content;
        private View customView;
        private CharSequence inputHint;
        private CharSequence inputPrefill;
        private ContentMode contentMode = ContentMode.NONE;

        private CharSequence secondaryText;
        private OnClickListener secondaryListener;
        private CharSequence primaryText;
        private OnClickListener primaryListener;
        private boolean cancelable = true;

        public Builder(@NonNull Context context) {
            this.context = context;
        }

        public Builder title(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder content(CharSequence content) {
            this.content = content;
            this.contentMode = ContentMode.TEXT;
            return this;
        }

        public Builder customView(@NonNull View view) {
            this.customView = view;
            this.contentMode = ContentMode.CUSTOM;
            return this;
        }

        public Builder input(CharSequence hint, CharSequence prefill) {
            this.inputHint = hint;
            this.inputPrefill = prefill;
            this.contentMode = ContentMode.INPUT;
            return this;
        }

        public Builder secondary(CharSequence text, @Nullable OnClickListener listener) {
            this.secondaryText = text;
            this.secondaryListener = listener;
            return this;
        }

        public Builder primary(CharSequence text, @Nullable OnClickListener listener) {
            this.primaryText = text;
            this.primaryListener = listener;
            return this;
        }

        public Builder cancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        public FshareDialog build() {
            return new FshareDialog(this);
        }

        public FshareDialog show() {
            FshareDialog dialog = build();
            dialog.show();
            return dialog;
        }
    }
}
