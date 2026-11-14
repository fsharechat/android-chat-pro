package cn.wildfire.chat.kit.conversation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import cn.wildfire.chat.app.main.MainActivity;
import cn.wildfire.chat.kit.audio.AudioRecorder;
import cn.wildfire.chat.kit.audio.AudioRecorderPanel;
import cn.wildfire.chat.kit.audio.RecordingGesture;
import cn.wildfire.chat.kit.conversation.mention.MentionSpan;
import cn.wildfire.chat.kit.widget.GlassEdgeEffectFactory;
import cn.wildfire.chat.kit.widget.InputAwareLayout;
import cn.wildfire.chat.kit.widget.KeyboardAwareLinearLayout;
import cn.wildfirechat.chat.R;

/** Real View geometry/state checks. Fixture never sends messages or opens the microphone. */
public final class ComposerInstrumentation extends Instrumentation {
    private Activity activity;
    private InputAwareLayout root;
    private ConversationInputPanel panel;
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = Activity.RESULT_OK;
        try {
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(this::checkDrafts);
            runOnMainSync(this::checkCancellation);
            for (boolean dark : new boolean[]{false, true}) checkTheme(dark);
            result.putString("stream", "\nPASS: " + checks + " composer checks; screenshots: external files/composer-*.png\n");
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(error) + "\n");
            status = Activity.RESULT_CANCELED;
        } finally { if (activity != null) runOnMainSync(this::removeFixture); }
        finish(status, result);
    }
    private void checkTheme(boolean dark) throws Exception {
        runOnMainSync(() -> createFixture(dark)); waitForIdleSync(); SystemClock.sleep(250);
        runOnMainSync(() -> {
            check(panel.getHeight() >= dp(64) && panel.getHeight() <= dp(84), "compact dock height=" + panel.getHeight());
            checkCentered((EditText) panel.findViewById(R.id.editText));
            View plus = panel.findViewById(R.id.extImageView);
            View capsule = panel.findViewById(R.id.composerCapsule);
            check(plus.getWidth() == dp(48) && plus.getHeight() == dp(48), "48dp plus frame");
            check(capsule.getHeight() == dp(48), "48dp single-line capsule");
            check(plus.getHeight() == capsule.getHeight() && plus.getTop() == capsule.getTop(), "plus circle matches capsule height and top");
            check(Color.red(panel.getResources().getColor(R.color.composer_fallback)) != Color.red(panel.getResources().getColor(R.color.composer_border)), "material boundary");
        });
        screenshot(dark, "empty");
        runOnMainSync(() -> {
            ((EditText) panel.findViewById(R.id.editText)).setText("测试草稿");
            check(panel.findViewById(R.id.extImageView).getVisibility() == View.VISIBLE, "plus with draft");
            check(panel.findViewById(R.id.sendButton).getVisibility() == View.VISIBLE, "send shown");
            check(panel.findViewById(R.id.audioImageView).getVisibility() == View.GONE, "send/voice exclusive");
            View send = panel.findViewById(R.id.sendButton);
            check(send.getWidth() - send.getPaddingLeft() - send.getPaddingRight() <= dp(18), "small send glyph");
        });
        waitForIdleSync(); screenshot(dark, "text");
        runOnMainSync(() -> {
            checkCentered((EditText) panel.findViewById(R.id.editText));
            panel.showVoiceInput();
            check(panel.findViewById(R.id.audioButton).getVisibility() == View.VISIBLE, "voice mode");
            View keyboard = panel.findViewById(R.id.composerKeyboard);
            check(keyboard.getLayoutParams().width == dp(48), "keyboard hit target");
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            View keyboard = panel.findViewById(R.id.composerKeyboard);
            check(keyboard.getWidth() - keyboard.getPaddingLeft() - keyboard.getPaddingRight() <= dp(21), "small keyboard glyph");
        });
        screenshot(dark, "voice");
        runOnMainSync(() -> panel.findViewById(R.id.emotionImageView).performClick()); waitForIdleSync();
        runOnMainSync(() -> {
            check(panel.findViewById(R.id.editText).getVisibility() == View.VISIBLE, "emoji exits voice");
            check(panel.findViewById(R.id.emotionContainerFrameLayout).getVisibility() == View.VISIBLE, "emoji shown");
            check(((EditText) panel.findViewById(R.id.editText)).getText().toString().equals("测试草稿"), "draft preserved");
            panel.findViewById(R.id.extImageView).performClick();
            check(panel.findViewById(R.id.extContainerContainerLayout).getVisibility() == View.VISIBLE, "more shown");
            check(panel.findViewById(R.id.emotionContainerFrameLayout).getVisibility() == View.GONE, "panels exclusive");
            panel.collapse(); check(!root.isInputOpen(), "collapse clears intent");
            simulateKeyboard("onKeyboardOpen", dp(260));
            panel.findViewById(R.id.extImageView).performClick(); panel.collapse();
            simulateKeyboard("onKeyboardClose", null);
            check(root.getCurrentInput() == null, "late keyboard callback cannot reopen panel");
            ((EditText) panel.findViewById(R.id.editText)).setText("第一行\n第二行\n第三行");
        });
        waitForIdleSync();
        runOnMainSync(() -> check(panel.getHeight() > dp(80) && panel.getHeight() < dp(180), "bounded multiline height"));
        screenshot(dark, "multiline"); runOnMainSync(this::removeFixture);
    }
    private void simulateKeyboard(String name, Integer height) {
        try {
            Method method = height == null ? KeyboardAwareLinearLayout.class.getDeclaredMethod(name) : KeyboardAwareLinearLayout.class.getDeclaredMethod(name, int.class);
            method.setAccessible(true);
            if (height == null) method.invoke(root); else method.invoke(root, height);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private void createFixture(boolean dark) {
        removeFixture();
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        ContextThemeWrapper context = new ContextThemeWrapper(activity, R.style.AppTheme);
        context.applyOverrideConfiguration(config);
        root = (InputAwareLayout) LayoutInflater.from(context).inflate(R.layout.conversation_activity, null);
        root.setBackgroundColor(context.getResources().getColor(R.color.paper));
        root.setFocusableInTouchMode(true); root.requestFocus();
        RecyclerView messages = root.findViewById(R.id.msgRecyclerView);
        messages.setLayoutManager(new LinearLayoutManager(context));
        messages.setEdgeEffectFactory(new GlassEdgeEffectFactory());
        messages.setAdapter(new RecyclerView.Adapter<Holder>() {
            @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
                TextView label = new TextView(context); label.setTextSize(16); label.setTextColor(context.getResources().getColor(R.color.ink));
                label.setPadding(dp(20), dp(14), dp(20), dp(14)); label.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(70)));
                return new Holder(label);
            }
            @Override public void onBindViewHolder(Holder holder, int position) { ((TextView) holder.itemView).setText("布局验证消息 " + (position + 1)); }
            @Override public int getItemCount() { return 12; }
        });
        panel = root.findViewById(R.id.inputPanelFrameLayout);
        panel.init((androidx.fragment.app.FragmentActivity) activity, root);
        panel.bindBackdrop(root.findViewById(R.id.swipeRefreshLayout));
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> messages.setPadding(0, 0, 0, panel.getHeight() + dp(8)));
        activity.addContentView(root, new ViewGroup.LayoutParams(-1, -1));
    }
    private static final class Holder extends RecyclerView.ViewHolder { Holder(View view) { super(view); } }
    private void removeFixture() {
        if (panel != null) panel.dispose();
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        panel = null; root = null;
    }
    private void checkCentered(EditText editor) {
        Paint.FontMetrics m = editor.getPaint().getFontMetrics();
        check(Math.abs(editor.getBaseline() + (m.ascent + m.descent) / 2 - editor.getHeight() / 2f) < dp(3), "vertical text center");
    }
    private void screenshot(boolean dark, String name) throws Exception {
        SystemClock.sleep(100); Bitmap bitmap = getUiAutomation().takeScreenshot(); check(bitmap != null, "screenshot");
        File path = new File(getTargetContext().getExternalFilesDir(null), "composer-" + (dark ? "dark-" : "light-") + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(path)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
    }
    private void checkDrafts() {
        SpannableStringBuilder text = new SpannableStringBuilder("@小明 你好");
        text.setSpan(new MentionSpan("user-1"), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Spanned restored = (Spanned) Draft.parseDraft(Draft.toDraftJson(text));
        MentionSpan[] mentions = restored.getSpans(0, restored.length(), MentionSpan.class);
        check(mentions.length == 1 && "user-1".equals(mentions[0].getUid()), "draft restores mentions");
        check(Draft.parseDraft("{bad json").length() == 0, "bad JSON safe");
        check(Draft.parseDraft("{\"content\":\"hi\",\"mentions\":[{\"start\":0,\"end\":99,\"uid\":\"u\"}]}").length() == 2, "bad mention range safe");
    }
    private void checkCancellation() {
        try {
            AudioRecorderPanel recording = new AudioRecorderPanel(activity);
            RecordingGesture gesture = (RecordingGesture) field("gesture").get(recording);
            File file = new File(activity.getCacheDir(), "composer-cancel-test.amr");
            try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(new byte[]{1, 2}); }
            final int[] sent = {0};
            recording.setRecordListener(new AudioRecorderPanel.OnRecordListener() {
                @Override public void onRecordSuccess(String path, int duration) { sent[0]++; }
                @Override public void onRecordFail(String reason) {}
                @Override public void onRecordStateChanged(AudioRecorderPanel.RecordState state) {}
            });
            field("currentAudioFile").set(recording, file.getAbsolutePath());
            field("startTime").setLong(recording, SystemClock.elapsedRealtime() - 2000);
            field("recorder").set(recording, new AudioRecorder(activity) { @Override public boolean stopRecord() { return true; } });
            gesture.begin(); MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            recording.onTouch(new Button(activity), cancel); cancel.recycle();
            check(sent[0] == 0, "ACTION_CANCEL never sends");
            check(!file.exists() && !recording.isRecording(), "cancel resets and removes file");
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private Field field(String name) throws Exception { Field field = AudioRecorderPanel.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private int dp(int value) { return Math.round(value * getTargetContext().getResources().getDisplayMetrics().density); }
}
