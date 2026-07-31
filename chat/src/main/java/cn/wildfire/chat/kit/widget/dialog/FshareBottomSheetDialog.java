package cn.wildfire.chat.kit.widget.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

import cn.wildfirechat.chat.R;

/**
 * 微信式底部弹出菜单。基于 material 的 {@link BottomSheetDialog}，
 * 复用它的升起动画与下拉关闭手势，只把内容换成本项目样式的卡片：
 * 上两角 12dp 圆角、52dp 菜单行、fog 分割线，隔 8dp 后一个整行「取消」。
 */
public class FshareBottomSheetDialog extends BottomSheetDialog {

    public interface Callback {
        void onSelect(int position, CharSequence text);
    }

    private final List<CharSequence> items = new ArrayList<>();
    private Callback callback;

    public FshareBottomSheetDialog(@NonNull Context context) {
        super(context, R.style.FshareBottomSheetDialog);
    }

    public FshareBottomSheetDialog items(@NonNull List<String> items) {
        this.items.clear();
        this.items.addAll(items);
        return this;
    }

    public FshareBottomSheetDialog callback(@Nullable Callback callback) {
        this.callback = callback;
        return this;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_bottom_sheet);

        // BottomSheetDialog 的 sheet 容器自带白底，会在圆角外露出直角，这里清成透明，
        // 圆角交给 bg_dialog_sheet。style 里也设了一遍，双保险。
        View sheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundResource(android.R.color.transparent);
        }

        LinearLayout container = findViewById(R.id.sheetItemContainer);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                container.addView(createDivider());
            }
            final int position = i;
            final CharSequence text = items.get(i);
            TextView item = (TextView) inflater.inflate(
                    R.layout.fshare_bottom_sheet_item, container, false);
            item.setText(text);
            item.setOnClickListener(v -> {
                // 先关菜单再回调，避免回调里弹二次确认时两个弹窗叠在一起
                dismiss();
                if (callback != null) {
                    callback.onSelect(position, text);
                }
            });
            container.addView(item);
        }

        findViewById(R.id.sheetCancel).setOnClickListener(v -> dismiss());
    }

    private View createDivider() {
        View divider = new View(getContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(getContext().getResources().getColor(R.color.fog));
        return divider;
    }
}
