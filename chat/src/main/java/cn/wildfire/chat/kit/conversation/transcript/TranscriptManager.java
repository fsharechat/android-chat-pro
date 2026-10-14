package cn.wildfire.chat.kit.conversation.transcript;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.SoundMessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.remote.ChatManager;
import cn.wildfirechat.remote.GeneralCallback2;

/**
 * UI 进程单例：SharedPreferences 持久化，AIDL 回调切回主线程，变化通过 LiveData 通知会话页刷新。
 */
public class TranscriptManager {
    private static final String PREFS = "voice_transcript";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_AUTO = "auto";

    private static TranscriptManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Long> changed = new MutableLiveData<>();
    private final TranscriptEngine engine;

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new TranscriptManager(context.getApplicationContext());
        }
    }

    public static TranscriptManager get() {
        return instance;
    }

    private TranscriptManager(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        engine = new TranscriptEngine(
                (uid, cb) -> ChatManager.Instance().speechToText(uid, new GeneralCallback2() {
                    @Override
                    public void onSuccess(String text) {
                        mainHandler.post(() -> cb.onSuccess(text));
                    }

                    @Override
                    public void onFail(int errorCode) {
                        mainHandler.post(() -> cb.onFail(errorCode));
                    }
                }),
                mainHandler::postDelayed,
                new TranscriptEngine.Persistence() {
                    @Override
                    public Map<Long, String[]> loadDone() {
                        Map<Long, String[]> out = new HashMap<>();
                        try {
                            JSONObject json = new JSONObject(sp.getString(KEY_ENTRIES, "{}"));
                            Iterator<String> keys = json.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                JSONArray v = json.getJSONArray(k);
                                out.put(Long.parseLong(k), new String[]{v.getString(0), v.getString(1)});
                            }
                        } catch (Exception e) {
                            // 数据损坏时丢弃，不影响使用
                        }
                        return out;
                    }

                    @Override
                    public void saveDone(Map<Long, String[]> done) {
                        JSONObject json = new JSONObject();
                        try {
                            for (Map.Entry<Long, String[]> e : done.entrySet()) {
                                json.put(String.valueOf(e.getKey()), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
                            }
                        } catch (Exception ignored) {
                        }
                        sp.edit().putString(KEY_ENTRIES, json.toString()).apply();
                    }

                    @Override
                    public boolean loadAuto() {
                        return sp.getBoolean(KEY_AUTO, false);
                    }

                    @Override
                    public void saveAuto(boolean on) {
                        sp.edit().putBoolean(KEY_AUTO, on).apply();
                    }
                },
                changed::setValue);
    }

    public TranscriptEngine engine() {
        return engine;
    }

    public LiveData<Long> changedLiveData() {
        return changed;
    }

    /** WfcUIKit.onReceiveMessage 在主线程调用：实时到达的他人语音交给自动队列 */
    public void onReceiveMessages(List<Message> messages) {
        if (messages == null || !engine.isAutoTranscribe()) {
            return;
        }
        long now = System.currentTimeMillis();
        long delta = ChatManager.Instance().getServerDeltaTime();
        for (Message m : messages) {
            if (m.direction == MessageDirection.Receive
                    && m.content instanceof SoundMessageContent
                    && m.messageUid > 0
                    && VoiceTranscriptRules.isRealtime(m.serverTime, delta, now)) {
                engine.enqueueAuto(m.messageUid);
            }
        }
    }
}
