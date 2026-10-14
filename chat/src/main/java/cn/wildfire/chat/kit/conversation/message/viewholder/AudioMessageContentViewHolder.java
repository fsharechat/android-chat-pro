package cn.wildfire.chat.kit.conversation.message.viewholder;

import android.graphics.drawable.AnimationDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;

import butterknife.Bind;
import butterknife.OnClick;
import cn.wildfire.chat.app.Config;
import cn.wildfire.chat.kit.annotation.EnableContextMenu;
import cn.wildfire.chat.kit.annotation.MessageContentType;
import cn.wildfire.chat.kit.annotation.MessageContextMenuItem;
import cn.wildfire.chat.kit.annotation.ReceiveLayoutRes;
import cn.wildfire.chat.kit.annotation.SendLayoutRes;
import cn.wildfire.chat.kit.conversation.message.model.UiMessage;
import cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine;
import cn.wildfire.chat.kit.conversation.transcript.TranscriptManager;
import cn.wildfire.chat.kit.conversation.transcript.VoiceTranscriptRules;
import cn.wildfire.chat.kit.third.utils.UIUtils;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.message.SoundMessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.message.core.MessageStatus;

@MessageContentType(SoundMessageContent.class)
@SendLayoutRes(resId = R.layout.conversation_item_audio_send)
@ReceiveLayoutRes(resId = R.layout.conversation_item_audio_receive)
@EnableContextMenu
public class AudioMessageContentViewHolder extends MediaMessageContentViewHolder {
    @Bind(R.id.audioImageView)
    ImageView ivAudio;
    @Bind(R.id.durationTextView)
    TextView durationTextView;
    @Bind(R.id.audioContentLayout)
    RelativeLayout contentLayout;
    @Nullable
    @Bind(R.id.playStatusIndicator)
    View playStatusIndicator;
    @Nullable
    @Bind(R.id.transcribePillTextView)
    TextView transcribePillTextView;
    @Bind(R.id.transcriptTextView)
    TextView transcriptTextView;

    public AudioMessageContentViewHolder(FragmentActivity context, RecyclerView.Adapter adapter, View itemView) {
        super(context, adapter, itemView);
    }

    @Override
    public void onBind(UiMessage message) {
        super.onBind(message);
        SoundMessageContent voiceMessage = (SoundMessageContent) message.message.content;
        int increment = UIUtils.getDisplayWidth() / 2 / Config.DEFAULT_MAX_AUDIO_RECORD_TIME_SECOND * voiceMessage.getDuration();

        durationTextView.setText(voiceMessage.getDuration() + "''");
        ViewGroup.LayoutParams params = contentLayout.getLayoutParams();
        params.width = UIUtils.dip2Px(65) + UIUtils.dip2Px(increment);
        contentLayout.setLayoutParams(params);
        if (message.message.direction == MessageDirection.Receive) {
            if (message.message.status != MessageStatus.Played) {
                playStatusIndicator.setVisibility(View.VISIBLE);
            } else {
                playStatusIndicator.setVisibility(View.GONE);
            }
        }
        bindTranscript(message);

        AnimationDrawable animation;
        if (message.isPlaying) {
            animation = (AnimationDrawable) ivAudio.getBackground();
            if (!animation.isRunning()) {
                animation.start();
            }
        } else {
            // TODO 不知道怎么回事，动画开始了，就停不下来, 所以采用这种方式
            ivAudio.setBackground(null);
            if (message.message.direction == MessageDirection.Send) {
                ivAudio.setBackgroundResource(R.drawable.audio_animation_right_list);
            } else {
                ivAudio.setBackgroundResource(R.drawable.audio_animation_left_list);
            }
        }

        // 下载完成，开始播放
        if (message.progress == 100 && message.message.direction == MessageDirection.Receive) {
            message.progress = 0;
            itemView.post(() -> {
                conversationViewModel.playAudioMessage(message);
            });
        }
    }

    @Override
    public void onViewRecycled() {
        // TODO 可实现语音是否持续播放、中断登录逻辑
    }

    @OnClick(R.id.audioContentLayout)
    public void onClick(View view) {
        File file = conversationViewModel.mediaMessageContentFile(message);
        if (file == null) {
            return;
        }
        if (file.exists()) {
            conversationViewModel.playAudioMessage(message);
        } else {
            if (message.isDownloading) {
                return;
            }
            conversationViewModel.downloadMedia(message, file);
        }
    }

    private void bindTranscript(UiMessage message) {
        TranscriptEngine engine = TranscriptManager.get().engine();
        long uid = message.message.messageUid;
        TranscriptEngine.Entry entry = engine.get(uid);
        boolean incoming = message.message.direction == MessageDirection.Receive;
        boolean played = message.message.status == MessageStatus.Played;

        if (transcribePillTextView != null) {
            boolean showPill = VoiceTranscriptRules.showPill(incoming, played, uid, entry, engine.isAutoTranscribe());
            transcribePillTextView.setVisibility(showPill ? View.VISIBLE : View.GONE);
            // ButterKnife 7.0.1 的 @OnClick 需要 @Optional，发送布局无此控件，改用手动监听
            transcribePillTextView.setOnClickListener(v -> transcribe(message));
        }

        // 文字块背景与语音气泡一致（接收灰、发送蓝），文字颜色沿用文本消息配色；
        // 发送方蓝底上提示色看不清，提示状态也用发送文本色
        int textColor = incoming ? R.color.message_text_receive_dark : R.color.message_text_send_dark;
        int hintColor = incoming ? R.color.mist : R.color.message_text_send_dark;
        int errorColor = incoming ? R.color.coral : R.color.message_text_send_dark;
        switch (VoiceTranscriptRules.block(entry)) {
            case LOADING:
                showTranscript(message, "转文字中…", hintColor, false);
                break;
            case ERROR:
                showTranscript(message, "转文字失败，点击重试", errorColor, true);
                break;
            case EMPTY:
                showTranscript(message, "未识别到文字", hintColor, false);
                break;
            case TEXT:
                showTranscript(message, entry.text, textColor, false);
                break;
            default:
                transcriptTextView.setVisibility(View.GONE);
                transcriptTextView.setOnClickListener(null);
                break;
        }
    }

    private void showTranscript(UiMessage message, String text, int colorRes, boolean retryOnClick) {
        transcriptTextView.setVisibility(View.VISIBLE);
        transcriptTextView.setText(text);
        transcriptTextView.setTextColor(ContextCompat.getColor(context, colorRes));
        if (retryOnClick) {
            transcriptTextView.setOnClickListener(v -> TranscriptManager.get().engine().request(message.message.messageUid));
        } else {
            transcriptTextView.setOnClickListener(null);
        }
    }

    /** 点「转文字」视为已读：消红点 */
    private void transcribe(UiMessage message) {
        conversationViewModel.markAudioPlayed(message);
        TranscriptManager.get().engine().request(message.message.messageUid);
    }

    @MessageContextMenuItem(tag = MessageContextMenuItemTags.TAG_TRANSCRIBE, title = "转文字", icon = R.drawable.ic_menu_copy, priority = 9)
    public void transcribeFromMenu(View itemView, UiMessage message) {
        transcribe(message);
    }

    @MessageContextMenuItem(tag = MessageContextMenuItemTags.TAG_HIDE_TRANSCRIPT, title = "隐藏文字", icon = R.drawable.ic_menu_clear, priority = 9)
    public void hideTranscript(View itemView, UiMessage message) {
        TranscriptManager.get().engine().hide(message.message.messageUid);
    }

    @Override
    public boolean contextMenuItemFilter(UiMessage uiMessage, String tag) {
        // 返回 true 表示过滤掉（不显示）该菜单项
        long uid = uiMessage.message.messageUid;
        TranscriptEngine.Entry entry = TranscriptManager.get().engine().get(uid);
        if (MessageContextMenuItemTags.TAG_TRANSCRIBE.equals(tag)) {
            return !VoiceTranscriptRules.showTranscribeMenu(uid, entry);
        }
        if (MessageContextMenuItemTags.TAG_HIDE_TRANSCRIPT.equals(tag)) {
            return !VoiceTranscriptRules.showHideMenu(uid, entry);
        }
        return super.contextMenuItemFilter(uiMessage, tag);
    }
}
