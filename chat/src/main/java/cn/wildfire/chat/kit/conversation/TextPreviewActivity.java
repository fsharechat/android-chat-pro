package cn.wildfire.chat.kit.conversation;

import android.content.Context;
import android.content.Intent;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import butterknife.Bind;
import cn.wildfire.chat.kit.WfcBaseActivity;
import cn.wildfirechat.chat.R;
import io.noties.markwon.Markwon;
import io.noties.markwon.SoftBreakAddsNewLinePlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * 长文本消息的「查看全文」页（对齐 iOS TextPreviewViewController）：整页渲染完整 Markdown，
 * 文字用系统选字——长按选中手指所在的词，拖动两端手柄调整范围后复制，而不是像气泡那样一律全选。
 */
public class TextPreviewActivity extends WfcBaseActivity {
    private static final String EXTRA_TEXT = "text";

    @Bind(R.id.contentTextView)
    TextView contentTextView;

    public static void start(Context context, String text) {
        Intent intent = new Intent(context, TextPreviewActivity.class);
        intent.putExtra(EXTRA_TEXT, text);
        context.startActivity(intent);
    }

    @Override
    protected int contentLayout() {
        return R.layout.activity_text_preview;
    }

    @Override
    protected void afterViews() {
        setTitle("全文");
        String text = getIntent().getStringExtra(EXTRA_TEXT);
        // 插件与聊天气泡（TextMessageContentViewHolder）保持一致，渲染结果相同
        Markwon.builder(this)
                .usePlugin(SoftBreakAddsNewLinePlugin.create())
                .usePlugin(TablePlugin.create(this))
                .usePlugin(StrikethroughPlugin.create())
                .build()
                .setMarkdown(contentTextView, text != null ? text : "");
        // 先开启可选，再换回 LinkMovementMethod：setTextIsSelectable 会装上不响应链接点击的
        // ArrowKeyMovementMethod，换回后链接可点，长按选字仍由 TextView 的 Editor 处理
        contentTextView.setTextIsSelectable(true);
        contentTextView.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
