package cn.wildfire.chat.kit.audio;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.UUID;
import cn.wildfirechat.chat.R;

/** Recording UI and gesture owner. System cancellation is always discard, never a send. */
public class AudioRecorderPanel implements View.OnTouchListener {
    private int maxDuration = 60000, minDuration = 1000, countDown = 10000;
    private long startTime;
    private float startY;
    private String currentAudioFile;
    private final RecordingGesture gesture = new RecordingGesture();
    private final Context context;
    private View rootView;
    private Button button;
    private AudioRecorder recorder;
    private OnRecordListener recordListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;
    private TextView stateText, timerText;
    private ImageView stateImage;
    private PopupWindow window;

    public AudioRecorderPanel(Context context) { this.context = context; }
    public void setMaxDuration(int seconds) { maxDuration = seconds * 1000; }
    public void setMinDuration(int seconds) { minDuration = seconds * 1000; }
    public void setCountDown(int seconds) { countDown = seconds * 1000; }
    public boolean isRecording() { return gesture.active(); }
    public void setRecordListener(OnRecordListener listener) { recordListener = listener; }
    public void attach(View root, Button button) {
        rootView = root;
        this.button = button;
        button.setOnTouchListener(this);
        button.setContentDescription("按住说话；辅助功能用户可双击开始，再次双击停止发送");
        // Touch handling consumes UP, so this click path is reserved for accessibility activation.
        button.setOnClickListener(v -> { if (isRecording()) finish(false); else startRecord(); });
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (isRecording()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
            }
            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { cancel(); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }
    public void deattch() {
        cancel();
        if (button != null) { button.setOnTouchListener(null); button.setOnClickListener(null); button.setAccessibilityDelegate(null); }
        button = null; rootView = null; window = null;
        stateText = timerText = null; stateImage = null;
    }
    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = event.getRawY();
                startRecord();
                break;
            case MotionEvent.ACTION_MOVE:
                if (isRecording()) {
                    gesture.move((startY - event.getRawY()) / context.getResources().getDisplayMetrics().density);
                    updateRecording();
                }
                break;
            case MotionEvent.ACTION_UP: finish(false); break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN: cancel(); break;
            default: break;
        }
        return true;
    }
    private void startRecord() {
        if (isRecording() || rootView == null || !rootView.isAttachedToWindow()) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Context owner = context;
            while (owner instanceof ContextWrapper && !(owner instanceof Activity)) owner = ((ContextWrapper) owner).getBaseContext();
            if (owner instanceof Activity) ActivityCompat.requestPermissions((Activity) owner, new String[] {Manifest.permission.RECORD_AUDIO}, 0x6A21);
            fail(context.getString(R.string.composer_record_permission));
            return; // A permission callback must never start a gesture the user has already released.
        }
        File directory = new File(context.getFilesDir(), "audio");
        if (!directory.exists() && !directory.mkdirs()) { fail("无法保存录音，请稍后重试"); return; }
        currentAudioFile = new File(directory, "voice-" + UUID.randomUUID() + ".amr").getAbsolutePath();
        recorder = new AudioRecorder(context);
        recorder.setOnInterrupted(() -> { cancel(); fail("录音已中断，请重试"); });
        if (!recorder.startRecord(currentAudioFile)) {
            recorder.stopRecord(); recorder = null;
            deleteCurrentFile();
            fail(context.getString(R.string.composer_record_error));
            return;
        }
        gesture.begin();
        startTime = SystemClock.elapsedRealtime();
        button.setContentDescription("录音中，双击停止并发送，也可执行取消操作");
        if (recordListener != null) recordListener.onRecordStateChanged(RecordState.START);
        showWindow();
        tick();
    }
    public void cancel() { finish(true); }
    private void finish(boolean cancelled) {
        boolean wasActive = gesture.active();
        boolean send = gesture.finish(cancelled);
        handler.removeCallbacks(ticker);
        long duration = SystemClock.elapsedRealtime() - startTime;
        boolean valid = recorder != null && recorder.stopRecord();
        recorder = null;
        if (window != null) window.dismiss();
        if (button != null) {
            button.setPressed(false);
            button.setContentDescription("按住说话；辅助功能用户可双击开始，再次双击停止发送");
        }
        String file = currentAudioFile;
        currentAudioFile = null;
        if (wasActive && recordListener != null) recordListener.onRecordStateChanged(RecordState.END);
        if (!send || !valid || duration < minDuration) {
            if (file != null) new File(file).delete();
            if (send) fail(duration < minDuration ? "说话时间太短" : "录音失败，请重试");
            return;
        }
        if (recordListener != null) recordListener.onRecordSuccess(file, (int) duration / 1000);
        else if (file != null) new File(file).delete();
    }
    private void deleteCurrentFile() {
        if (currentAudioFile != null) new File(currentAudioFile).delete();
        currentAudioFile = null;
    }
    private void fail(String message) { if (recordListener != null) recordListener.onRecordFail(message); }
    private void tick() {
        if (!isRecording()) return;
        if (SystemClock.elapsedRealtime() - startTime >= maxDuration) { finish(false); return; }
        updateRecording();
        handler.postDelayed(ticker, 200);
    }
    private void showWindow() {
        if (window == null) {
            View view = View.inflate(context, R.layout.audio_popup_wi_vo, null);
            stateImage = view.findViewById(R.id.rc_audio_state_image);
            stateText = view.findViewById(R.id.rc_audio_state_text);
            timerText = view.findViewById(R.id.rc_audio_timer);
            window = new PopupWindow(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setFocusable(false);
            window.setOutsideTouchable(false);
            window.setTouchable(false);
        }
        window.showAtLocation(rootView, Gravity.CENTER, 0, 0);
    }
    private void updateRecording() {
        if (!isRecording() || window == null) return;
        long elapsed = SystemClock.elapsedRealtime() - startTime;
        stateText.setVisibility(View.VISIBLE);
        stateText.setBackgroundResource(R.drawable.bg_voice_popup);
        if (gesture.cancelArmed()) {
            stateImage.setVisibility(View.VISIBLE);
            stateImage.setImageResource(R.mipmap.ic_volume_cancel);
            timerText.setVisibility(View.GONE);
            stateText.setText("松开取消");
        } else if (elapsed >= maxDuration - countDown) {
            stateImage.setVisibility(View.GONE);
            timerText.setVisibility(View.VISIBLE);
            timerText.setText(String.valueOf(Math.max(1, (maxDuration - elapsed) / 1000)));
            stateText.setText("松开发送，上滑取消");
        } else {
            timerText.setVisibility(View.GONE);
            stateImage.setVisibility(View.VISIBLE);
            int volume = Math.min(7, recorder.getMaxAmplitude() * 8 / 32768);
            int[] levels = {R.mipmap.ic_volume_1, R.mipmap.ic_volume_2, R.mipmap.ic_volume_3, R.mipmap.ic_volume_4,
                    R.mipmap.ic_volume_5, R.mipmap.ic_volume_6, R.mipmap.ic_volume_7, R.mipmap.ic_volume_8};
            stateImage.setImageResource(levels[volume]);
            stateText.setText((elapsed / 1000) + "秒 · 松开发送，上滑取消");
        }
    }
    public interface OnRecordListener {
        void onRecordSuccess(String audioFile, int duration);
        void onRecordFail(String reason);
        void onRecordStateChanged(RecordState state);
    }
    public enum RecordState { START, RECORDING, TO_CANCEL, TO_TIMEOUT, END }
}
