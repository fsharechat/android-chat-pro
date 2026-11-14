package cn.wildfire.chat.kit.conversation;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.Selection;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProviders;

import com.lqr.emoji.EmotionLayout;
import com.lqr.emoji.IEmotionExtClickListener;
import com.lqr.emoji.IEmotionSelectedListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnTextChanged;
import cn.wildfire.chat.kit.audio.AudioRecorderPanel;
import cn.wildfire.chat.kit.conversation.ext.core.ConversationExtension;
import cn.wildfire.chat.kit.conversation.mention.MentionGroupMemberActivity;
import cn.wildfire.chat.kit.conversation.mention.MentionSpan;
import cn.wildfire.chat.kit.group.GroupViewModel;
import cn.wildfire.chat.kit.widget.InputAwareLayout;
import cn.wildfire.chat.kit.widget.KeyboardHeightFrameLayout;
import cn.wildfire.chat.kit.widget.ViewPagerFixed;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.message.TextMessageContent;
import cn.wildfirechat.message.TypingMessageContent;
import cn.wildfirechat.model.Conversation;
import cn.wildfirechat.model.ConversationInfo;
import cn.wildfirechat.model.GroupInfo;

import static cn.wildfire.chat.kit.conversation.ConversationActivity.REQUEST_PICK_MENTION_CONTACT;

public class ConversationInputPanel extends FrameLayout implements IEmotionSelectedListener {
    @Bind(R.id.audioImageView)
    ImageView audioImageView;
    @Bind(R.id.audioButton)
    Button audioButton;
    @Bind(R.id.editText)
    EditText editText;
    @Bind(R.id.emotionImageView)
    ImageView emotionImageView;
    @Bind(R.id.extImageView)
    ImageView extImageView;
    @Bind(R.id.sendButton)
    ImageView sendButton;

    @Bind(R.id.emotionContainerFrameLayout)
    KeyboardHeightFrameLayout emotionContainerFrameLayout;
    @Bind(R.id.emotionLayout)
    EmotionLayout emotionLayout;
    @Bind(R.id.extContainerContainerLayout)
    KeyboardHeightFrameLayout extContainerFrameLayout;

    @Bind(R.id.inputPanelFrameLayout)
    FrameLayout inputContainerFrameLayout;
    @Bind(R.id.conversationExtViewPager)
    ViewPagerFixed extViewPager;

    ConversationExtension extension;
    private Conversation conversation;
    private ConversationViewModel conversationViewModel;
    private InputAwareLayout rootLinearLayout;
    private FragmentActivity activity;
    private AudioRecorderPanel audioRecorderPanel;

    private final ComposerState state = new ComposerState();
    private ImageView keyboardButton;
    private View tail;
    private cn.wildfire.chat.kit.widget.ComposerGlassView glass;
    private cn.wildfire.chat.kit.widget.GlassBackdropRenderer backdrop;
    private View plusView, capsule;
    private boolean restoringDraft;

    private long lastTypingTime;
    private String draftString;
    private static final int TYPING_INTERVAL_IN_SECOND = 10;
    private SharedPreferences sharedPreferences;

    private OnConversationInputPanelStateChangeListener onConversationInputPanelStateChangeListener;

    public ConversationInputPanel(@NonNull Context context) {
        super(context);
    }

    public ConversationInputPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

    }

    public ConversationInputPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public ConversationInputPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

    }

    public void setOnConversationInputPanelStateChangeListener(OnConversationInputPanelStateChangeListener onConversationInputPanelStateChangeListener) {
        this.onConversationInputPanelStateChangeListener = onConversationInputPanelStateChangeListener;
    }

    public void bind(FragmentActivity activity, InputAwareLayout rootInputAwareLayout) {

    }

    public void setupConversation(ConversationViewModel conversationViewModel, Conversation conversation) {
        this.conversationViewModel = conversationViewModel;
        this.conversation = conversation;
        this.extension.bind(conversationViewModel, conversation);

        setDraft();
    }

    public void init(FragmentActivity activity, InputAwareLayout rootInputAwareLayout) {
        LayoutInflater.from(getContext()).inflate(R.layout.conversation_input_panel, this, true);
        ButterKnife.bind(this, this);

        this.activity = activity;
        this.rootLinearLayout = rootInputAwareLayout;
        keyboardButton = findViewById(R.id.composerKeyboard);
        tail = findViewById(R.id.composerTail);
        glass = findViewById(R.id.composerGlass);
        plusView = findViewById(R.id.extImageView);
        capsule = findViewById(R.id.composerCapsule);
        keyboardButton.setOnClickListener(v -> showKeyboard());
        editText.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && state.surface() != ComposerState.Surface.KEYBOARD) showKeyboard();
            return false;
        });

        this.extension = new ConversationExtension(activity, this, extViewPager);

        // 表情/扩展面板都按键盘高度显示，先用已知的键盘高度预置一次，
        // 之后键盘每次弹出时同步更新，避免键盘与面板互相切换时高度跳变
        post(this::syncPanelHeightWithKeyboard);
        rootLinearLayout.addOnKeyboardShownListener(this::syncPanelHeightWithKeyboard);

        sharedPreferences = getContext().getSharedPreferences("sticker", Context.MODE_PRIVATE);

        // emotion
        emotionLayout.attachEditText(editText);
        emotionLayout.setEmotionAddVisiable(true);
        emotionLayout.setEmotionSettingVisiable(true);

        editText.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                Editable buffer = ((EditText) v).getText();
                // If the cursor is at the end of a MentionSpan then remove the whole span
                int start = Selection.getSelectionStart(buffer);
                int end = Selection.getSelectionEnd(buffer);
                if (start == end) {
                    MentionSpan[] mentions = buffer.getSpans(start, end, MentionSpan.class);
                    if (mentions.length > 0 && buffer.getSpanEnd(mentions[0]) == start) {
                        buffer.replace(
                                buffer.getSpanStart(mentions[0]),
                                buffer.getSpanEnd(mentions[0]),
                                ""
                        );
                        buffer.removeSpan(mentions[0]);
                        return true;
                    }
                }
                return false;
            }
            return false;
        });

        // audio record panel
        audioRecorderPanel = new AudioRecorderPanel(getContext());
        audioRecorderPanel.setRecordListener(new AudioRecorderPanel.OnRecordListener() {
            @Override
            public void onRecordSuccess(String audioFile, int duration) {
                //发送文件
                File file = new File(audioFile);
                if (file.exists()) {
                    conversationViewModel.sendAudioFile(Uri.parse(audioFile), duration);
                }
            }

            @Override
            public void onRecordFail(String reason) {
                Toast.makeText(activity, reason, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecordStateChanged(AudioRecorderPanel.RecordState state) {
                boolean active = audioRecorderPanel.isRecording();
                extImageView.setEnabled(!active);
                emotionImageView.setEnabled(!active);
                keyboardButton.setEnabled(!active);
                if (state == AudioRecorderPanel.RecordState.START) {
                    TypingMessageContent content = new TypingMessageContent(TypingMessageContent.TYPING_VOICE);
                    conversationViewModel.sendMessage(content);
                }
            }
        });

        render();

        audioRecorderPanel.attach(rootLinearLayout, audioButton);

        // emotion
        emotionLayout.setEmotionSelectedListener(this);
        emotionLayout.setEmotionExtClickListener(new IEmotionExtClickListener() {
            @Override
            public void onEmotionAddClick(View view) {
                Toast.makeText(activity, "add", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onEmotionSettingClick(View view) {
                Toast.makeText(activity, "setting", Toast.LENGTH_SHORT).show();
            }
        });

    }

    @OnClick(R.id.extImageView)
    void onExtImageViewClick() {
        if (state.surface() == ComposerState.Surface.MORE) { collapse(); return; }
        if (isRecording()) return;
        state.show(ComposerState.Surface.MORE);
        rootLinearLayout.show(editText, extContainerFrameLayout);
        render();
        notifyPanelExpanded();
    }

    @OnClick(R.id.emotionImageView)
    void onEmotionImageViewClick() {
        if (isRecording()) return;
        if (state.surface() == ComposerState.Surface.EMOJI) { showKeyboard(); return; }
        state.show(ComposerState.Surface.EMOJI);
        rootLinearLayout.show(editText, emotionContainerFrameLayout);
        render();
        notifyPanelExpanded();
    }

    @OnTextChanged(value = R.id.editText, callback = OnTextChanged.Callback.TEXT_CHANGED)
    void onInputTextChanged(CharSequence s, int start, int before, int count) {
        if (!restoringDraft && conversation != null && activity.getCurrentFocus() == editText) {
            if (conversation.type == Conversation.ConversationType.Group) {
                if (count == 1 && s.charAt(start) == '@') {
//                    if (start == 0 || s.charAt(start - 1) == ' ') {
                    mentionGroupMember();
//                    }
                }
            }
        }
    }

    private void mentionGroupMember() {
        Intent intent = new Intent(activity, MentionGroupMemberActivity.class);
        GroupViewModel groupViewModel = ViewModelProviders.of(activity).get(GroupViewModel.class);
        GroupInfo groupInfo = groupViewModel.getGroupInfo(conversation.target, false);
        intent.putExtra("groupInfo", groupInfo);
        activity.startActivityForResult(intent, REQUEST_PICK_MENTION_CONTACT);
    }

    @OnTextChanged(value = R.id.editText, callback = OnTextChanged.Callback.AFTER_TEXT_CHANGED)
    void afterInputTextChanged(Editable editable) {
        render();
        if (!restoringDraft && conversation != null && editable.toString().trim().length() > 0 && editText.hasFocus()) {
            notifyTyping(TypingMessageContent.TYPING_TEXT);
        }
    }

    @OnClick(R.id.audioImageView)
    public void showRecordPanel() { showVoiceInput(); }

    public void showVoiceInput() {
        if (isRecording()) return;
        state.enterVoice();
        rootLinearLayout.hideCurrentInput(editText);
        render();
    }

    private void showKeyboard() {
        if (isRecording()) return;
        state.show(ComposerState.Surface.KEYBOARD);
        render();
        rootLinearLayout.showSoftkey(editText);
    }

    @OnClick(R.id.sendButton)
    void sendMessage() {
        Editable content = editText.getText();
        if (TextUtils.isEmpty(content.toString().trim()) || android.view.inputmethod.BaseInputConnection.getComposingSpanStart(content) >= 0) {
            return;
        }

        TextMessageContent txtContent = new TextMessageContent(content.toString().trim());
        if (conversation.type == Conversation.ConversationType.Group) {
            MentionSpan[] mentions = content.getSpans(0, content.length(), MentionSpan.class);
            if (mentions != null && mentions.length > 0) {
                txtContent.mentionedType = 1;
                List<String> mentionedIds = new ArrayList<>();
                for (MentionSpan span : mentions) {
                    if (!mentionedIds.contains(span.getUid())) {
                        mentionedIds.add(span.getUid());
                    }
                    // mentionAll
                    if (span.isMentionAll()) {
                        txtContent.mentionedType = 2;
                        break;
                    }
                }
                if (txtContent.mentionedType == 1) {
                    txtContent.mentionedTargets = mentionedIds;
                }
            }
        }
        conversationViewModel.sendTextMsg(txtContent);
        editText.setText("");
    }

    public void onKeyboardShown() {
        if (rootLinearLayout.isKeyboardRequested()) state.show(ComposerState.Surface.KEYBOARD);
        syncPanelHeightWithKeyboard();
        render();
    }
    public void onKeyboardHidden() {
        if (state.surface() == ComposerState.Surface.KEYBOARD && !rootLinearLayout.isKeyboardRequested()) state.collapse();
        render();
    }
    private void setDraft() {
        ConversationInfo info = conversationViewModel.getConversationInfo(conversation);
        if (info == null) return;
        draftString = info.draft;
        restoringDraft = true;
        try { editText.setText(Draft.parseDraft(draftString)); }
        finally { restoringDraft = false; }
        editText.setSelection(editText.length());
        render();
    }
    public void onActivityResume() { if (backdrop != null) backdrop.start(); }
    public void onActivityPause() {
        if (backdrop != null) backdrop.stop();
        if (audioRecorderPanel != null) audioRecorderPanel.cancel();
        if (conversationViewModel == null) return;
        Editable editable = editText.getText();
        String draft = editable.toString().trim().isEmpty() ? null : Draft.toDraftJson(editable);
        if (!TextUtils.equals(draftString, draft)) {
            conversationViewModel.saveDraft(conversation, draft);
            draftString = draft;
        }
    }
    public void dispose() {
        if (audioRecorderPanel != null) audioRecorderPanel.deattch();
        if (backdrop != null) { backdrop.close(); backdrop = null; }
    }
    public void bindBackdrop(View source) {
        backdrop = new cn.wildfire.chat.kit.widget.GlassBackdropRenderer(source, glass);
        backdrop.start();
    }
    public boolean isRecording() { return audioRecorderPanel != null && audioRecorderPanel.isRecording(); }
    private void syncPanelHeightWithKeyboard() {
        int height = rootLinearLayout.getKeyboardHeight();
        emotionContainerFrameLayout.applyPanelHeight(height);
        extContainerFrameLayout.applyPanelHeight(height);
    }
    private void render() {
        if (tail == null) return;
        boolean voice = state.isVoice();
        boolean hasText = !editText.getText().toString().trim().isEmpty();
        editText.setVisibility(voice ? GONE : VISIBLE);
        audioButton.setVisibility(voice ? VISIBLE : GONE);
        keyboardButton.setVisibility(voice ? VISIBLE : GONE);
        tail.setVisibility(voice ? GONE : VISIBLE);
        sendButton.setVisibility(hasText ? VISIBLE : GONE);
        audioImageView.setVisibility(hasText ? GONE : VISIBLE);
        boolean more = state.surface() == ComposerState.Surface.MORE;
        extImageView.setImageResource(more ? R.drawable.ic_composer_close : R.drawable.ic_composer_plus);
        extImageView.setContentDescription(getContext().getString(more ? R.string.composer_close_more : R.string.composer_more));
        boolean emoji = state.surface() == ComposerState.Surface.EMOJI;
        emotionImageView.setImageResource(emoji ? R.drawable.ic_composer_keyboard : R.drawable.ic_composer_emoji);
        emotionImageView.setPadding(dp(emoji ? 14 : 13), dp(emoji ? 14 : 13), dp(emoji ? 14 : 13), dp(emoji ? 14 : 13));
        emotionImageView.setContentDescription(getContext().getString(emoji ? R.string.composer_keyboard : R.string.composer_emoji));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void notifyPanelExpanded() {
        if (onConversationInputPanelStateChangeListener != null) onConversationInputPanelStateChangeListener.onInputPanelExpanded();
    }
    void collapse() {
        if (audioRecorderPanel != null) audioRecorderPanel.cancel();
        state.collapse();
        extension.reset();
        rootLinearLayout.hideCurrentInput(editText);
        render();
        if (onConversationInputPanelStateChangeListener != null) onConversationInputPanelStateChangeListener.onInputPanelCollapsed();
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (glass != null) glass.setShapes(plusView, capsule);
        if (changed && backdrop != null) backdrop.requestRefresh();
    }

    private void notifyTyping(int type) {
        if (conversation.type == Conversation.ConversationType.Single) {
            long now = System.currentTimeMillis();
            if (now - lastTypingTime > TYPING_INTERVAL_IN_SECOND * 1000) {
                lastTypingTime = now;
                TypingMessageContent content = new TypingMessageContent(type);
                conversationViewModel.sendMessage(content);
            }
        }
    }

    @Override
    public void onEmojiSelected(String key) {
//        LogUtils.e("onEmojiSelected : " + key);
    }

    @Override
    public void onStickerSelected(String categoryName, String stickerName, String stickerBitmapPath) {
        String remoteUrl = sharedPreferences.getString(stickerBitmapPath, null);
        conversationViewModel.sendStickerMsg(stickerBitmapPath, remoteUrl);
    }


    public interface OnConversationInputPanelStateChangeListener {
        /**
         * 输入面板展开
         */
        void onInputPanelExpanded();

        /**
         * 输入面板关闭
         */
        void onInputPanelCollapsed();
    }
}
