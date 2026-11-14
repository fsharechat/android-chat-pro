package cn.wildfire.chat.kit.audio;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Main-thread microphone owner. Every finish path releases the encoder and audio focus. */
public class AudioRecorder implements AudioManager.OnAudioFocusChangeListener {
    private final Context context;
    private AudioManager audioManager;
    private MediaRecorder mediaRecorder;
    private AudioFocusRequest focusRequest;
    private boolean started, hasFocus;
    private Runnable onInterrupted;

    public AudioRecorder(Context context) { this.context = context; }
    public void setOnInterrupted(Runnable callback) { onInterrupted = callback; }
    public boolean startRecord(String output) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        try {
            int focus;
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setOnAudioFocusChangeListener(this, new Handler(Looper.getMainLooper())).build();
                focus = audioManager.requestAudioFocus(focusRequest);
            } else {
                focus = audioManager.requestAudioFocus(this, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
            hasFocus = focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) return false;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setAudioSamplingRate(8000);
            mediaRecorder.setAudioEncodingBitRate(7950);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setOutputFile(output);
            mediaRecorder.setOnErrorListener((recorder, what, extra) -> interrupt());
            mediaRecorder.prepare();
            mediaRecorder.start();
            started = true;
            return true;
        } catch (Exception failure) {
            stopRecord();
            return false;
        }
    }
    public boolean stopRecord() {
        boolean valid = started;
        started = false;
        if (mediaRecorder != null) {
            mediaRecorder.setOnErrorListener(null);
            try { if (valid) mediaRecorder.stop(); }
            catch (RuntimeException tooShortOrInterrupted) { valid = false; }
            finally { mediaRecorder.release(); mediaRecorder = null; }
        }
        if (audioManager != null && hasFocus) {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            else audioManager.abandonAudioFocus(this);
        }
        hasFocus = false;
        focusRequest = null;
        return valid;
    }
    private void interrupt() {
        if (!started) return;
        if (onInterrupted != null) onInterrupted.run();
        else stopRecord();
    }
    @Override public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interrupt();
    }
    public int getMaxAmplitude() {
        try { return started && mediaRecorder != null ? mediaRecorder.getMaxAmplitude() : 0; }
        catch (RuntimeException unavailable) { return 0; }
    }
}
