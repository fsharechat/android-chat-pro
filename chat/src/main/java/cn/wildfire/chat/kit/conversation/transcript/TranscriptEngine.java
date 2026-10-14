package cn.wildfire.chat.kit.conversation.transcript;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 语音转文字状态机（纯 Java，便于单测）。所有方法与回调都要求在同一线程（主线程）调用。
 * 协议层对非 MS 请求不设超时，这里用 TIMEOUT_MS 兜底，超时后迟到的结果丢弃。
 */
public class TranscriptEngine {
    public static final long TIMEOUT_MS = 15000;
    public static final int AUTO_CONCURRENCY = 2;

    public enum Status { LOADING, DONE, ERROR }

    public static final class Entry {
        public final Status status;
        public final String text;
        public final boolean hidden;

        public Entry(Status status, String text, boolean hidden) {
            this.status = status;
            this.text = text;
            this.hidden = hidden;
        }
    }

    public interface ResultCallback {
        void onSuccess(String text);

        void onFail(int code);
    }

    public interface Transport {
        void speechToText(long uid, ResultCallback cb);
    }

    public interface Scheduler {
        void postDelayed(Runnable r, long delayMs);
    }

    public interface Persistence {
        Map<Long, String[]> loadDone();

        void saveDone(Map<Long, String[]> done);

        boolean loadAuto();

        void saveAuto(boolean on);
    }

    public interface Listener {
        void onChanged(long uid);
    }

    private final Transport transport;
    private final Scheduler scheduler;
    private final Persistence persistence;
    private final Listener listener;

    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, Integer> inflight = new HashMap<>();
    private final Deque<Long> autoQueue = new ArrayDeque<>();
    private final Set<Long> autoRunning = new HashSet<>();
    private boolean autoTranscribe;
    private int seq = 0;

    public TranscriptEngine(Transport transport, Scheduler scheduler, Persistence persistence, Listener listener) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.persistence = persistence;
        this.listener = listener;
        for (Map.Entry<Long, String[]> e : persistence.loadDone().entrySet()) {
            String[] v = e.getValue();
            entries.put(e.getKey(), new Entry(Status.DONE, v[0], "1".equals(v[1])));
        }
        this.autoTranscribe = persistence.loadAuto();
    }

    public Entry get(long uid) {
        return entries.get(uid);
    }

    public void request(long uid) {
        Entry cur = entries.get(uid);
        if (cur != null && cur.status == Status.LOADING) {
            if (cur.hidden) {
                put(uid, new Entry(Status.LOADING, "", false));
                persist();
            }
            return;
        }
        if (cur != null && cur.status == Status.DONE) {
            if (cur.hidden) {
                put(uid, new Entry(Status.DONE, cur.text, false));
                persist();
            }
            return;
        }
        final int gen = ++seq;
        inflight.put(uid, gen);
        put(uid, new Entry(Status.LOADING, "", false));
        scheduler.postDelayed(() -> finish(uid, gen, null), TIMEOUT_MS);
        transport.speechToText(uid, new ResultCallback() {
            @Override
            public void onSuccess(String text) {
                finish(uid, gen, text == null ? "" : text);
            }

            @Override
            public void onFail(int code) {
                finish(uid, gen, null);
            }
        });
    }

    public void hide(long uid) {
        Entry cur = entries.get(uid);
        if (cur == null) {
            return;
        }
        put(uid, new Entry(cur.status, cur.text, true));
        persist();
    }

    public boolean isAutoTranscribe() {
        return autoTranscribe;
    }

    public void setAutoTranscribe(boolean on) {
        autoTranscribe = on;
        if (!on) {
            autoQueue.clear();
        }
        persistence.saveAuto(on);
    }

    /** 实时收到的他人语音：开关打开时排队自动识别，已有结果或已在队列中的跳过 */
    public void enqueueAuto(long uid) {
        if (!autoTranscribe || entries.containsKey(uid) || autoQueue.contains(uid) || autoRunning.contains(uid)) {
            return;
        }
        autoQueue.add(uid);
        drain();
    }

    private void drain() {
        while (autoRunning.size() < AUTO_CONCURRENCY && !autoQueue.isEmpty()) {
            long uid = autoQueue.poll();
            Entry cur = entries.get(uid);
            if (cur != null && cur.status != Status.ERROR) {
                continue;
            }
            autoRunning.add(uid);
            request(uid);
        }
    }

    private void finish(long uid, int gen, String text) {
        Integer current = inflight.get(uid);
        if (current == null || current != gen) {
            return;
        }
        inflight.remove(uid);
        Entry cur = entries.get(uid);
        boolean hidden = cur != null && cur.hidden;
        put(uid, text == null ? new Entry(Status.ERROR, "", hidden) : new Entry(Status.DONE, text, hidden));
        persist();
        if (autoRunning.remove(uid)) {
            drain();
        }
    }

    private void put(long uid, Entry entry) {
        entries.put(uid, entry);
        listener.onChanged(uid);
    }

    private void persist() {
        Map<Long, String[]> done = new HashMap<>();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            if (v.status == Status.DONE) {
                done.put(e.getKey(), new String[]{v.text, v.hidden ? "1" : "0"});
            }
        }
        persistence.saveDone(done);
    }
}
