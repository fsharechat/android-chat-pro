package cn.wildfire.chat.kit.conversation.transcript;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TranscriptEngineTest {

    static class Call {
        final long uid;
        final TranscriptEngine.ResultCallback cb;
        Call(long uid, TranscriptEngine.ResultCallback cb) { this.uid = uid; this.cb = cb; }
    }

    final List<Call> calls = new ArrayList<>();
    final List<Runnable> timers = new ArrayList<>();
    final List<Long> changed = new ArrayList<>();
    Map<Long, String[]> saved = new HashMap<>();
    boolean savedAuto = false;
    TranscriptEngine engine;

    @Before
    public void setUp() {
        engine = newEngine();
    }

    TranscriptEngine newEngine() {
        return new TranscriptEngine(
                (uid, cb) -> calls.add(new Call(uid, cb)),
                (r, delay) -> {
                    assertEquals(TranscriptEngine.TIMEOUT_MS, delay);
                    timers.add(r);
                },
                new TranscriptEngine.Persistence() {
                    @Override public Map<Long, String[]> loadDone() { return new HashMap<>(saved); }
                    @Override public void saveDone(Map<Long, String[]> done) { saved = new HashMap<>(done); }
                    @Override public boolean loadAuto() { return savedAuto; }
                    @Override public void saveAuto(boolean on) { savedAuto = on; }
                },
                changed::add);
    }

    @Test
    public void successGoesLoadingThenDoneAndPersists() {
        engine.request(7L);
        assertEquals(TranscriptEngine.Status.LOADING, engine.get(7L).status);

        calls.get(0).cb.onSuccess("你好");

        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
        assertEquals("你好", engine.get(7L).text);
        assertEquals("你好", saved.get(7L)[0]);
        assertTrue(changed.size() >= 2);
    }

    @Test
    public void failureIsErrorAndRetryable() {
        engine.request(7L);
        calls.get(0).cb.onFail(248);
        assertEquals(TranscriptEngine.Status.ERROR, engine.get(7L).status);
        assertFalse(saved.containsKey(7L));

        engine.request(7L);
        assertEquals(2, calls.size());
    }

    @Test
    public void requestWhileLoadingIsNoop() {
        engine.request(7L);
        engine.request(7L);

        assertEquals(1, calls.size());
    }

    @Test
    public void timeoutTurnsLoadingIntoError() {
        engine.request(7L);
        timers.get(0).run();

        assertEquals(TranscriptEngine.Status.ERROR, engine.get(7L).status);
    }

    @Test
    public void lateResultAfterTimeoutIsIgnored() {
        engine.request(7L);
        timers.get(0).run();
        calls.get(0).cb.onSuccess("late");

        assertEquals(TranscriptEngine.Status.ERROR, engine.get(7L).status);
    }

    @Test
    public void timeoutAfterSuccessIsIgnored() {
        engine.request(7L);
        calls.get(0).cb.onSuccess("ok");
        timers.get(0).run();

        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
    }

    @Test
    public void hideThenRequestUnhidesWithoutNetwork() {
        engine.request(7L);
        calls.get(0).cb.onSuccess("a");
        engine.hide(7L);
        assertTrue(engine.get(7L).hidden);
        assertEquals("1", saved.get(7L)[1]);

        engine.request(7L);

        assertFalse(engine.get(7L).hidden);
        assertEquals(1, calls.size());
    }

    @Test
    public void hideWhileLoadingStaysHidden() {
        engine.request(7L);
        engine.hide(7L);
        calls.get(0).cb.onSuccess("a");

        assertTrue(engine.get(7L).hidden);
        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
    }

    @Test
    public void restoresDoneEntriesAndAutoFlag() {
        saved.put(9L, new String[]{"旧", "0"});
        savedAuto = true;

        TranscriptEngine restored = newEngine();

        assertEquals("旧", restored.get(9L).text);
        assertTrue(restored.isAutoTranscribe());
        assertNull(restored.get(10L));
    }

    @Test
    public void enqueueAutoIgnoredWhenOff() {
        engine.enqueueAuto(1L);

        assertTrue(calls.isEmpty());
    }

    @Test
    public void enqueueAutoRunsTwoAtATimeAndDedupes() {
        engine.setAutoTranscribe(true);
        assertTrue(savedAuto);

        engine.enqueueAuto(1L);
        engine.enqueueAuto(2L);
        engine.enqueueAuto(3L);
        engine.enqueueAuto(1L);
        assertEquals(2, calls.size());

        calls.get(0).cb.onSuccess("1");
        assertEquals(3, calls.size());
        assertEquals(3L, calls.get(2).uid);

        calls.get(1).cb.onFail(4);
        calls.get(2).cb.onSuccess("3");
        assertEquals(3, calls.size());
    }

    @Test
    public void enqueueAutoSkipsAlreadyTranscribed() {
        engine.request(1L);
        calls.get(0).cb.onSuccess("x");
        engine.setAutoTranscribe(true);

        engine.enqueueAuto(1L);

        assertEquals(1, calls.size());
    }

    @Test
    public void autoSlotIsFreedOnTimeout() {
        engine.setAutoTranscribe(true);
        engine.enqueueAuto(1L);
        engine.enqueueAuto(2L);
        engine.enqueueAuto(3L);

        timers.get(0).run();

        assertEquals(3, calls.size());
    }

    @Test
    public void hideWhileLoadingThenRequestUnhidesWithoutSecondCall() {
        engine.request(7L);
        engine.hide(7L);
        assertTrue(engine.get(7L).hidden);

        engine.request(7L);

        assertEquals(TranscriptEngine.Status.LOADING, engine.get(7L).status);
        assertFalse(engine.get(7L).hidden);
        assertEquals(1, calls.size());

        calls.get(0).cb.onSuccess("a");

        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
        assertFalse(engine.get(7L).hidden);
    }

    @Test
    public void manuallyCompletedQueuedUidDoesNotLeakAutoSlot() {
        engine.setAutoTranscribe(true);
        engine.enqueueAuto(1L);
        engine.enqueueAuto(2L);
        engine.enqueueAuto(3L);
        assertEquals(2, calls.size());

        engine.request(3L);
        assertEquals(3, calls.size());
        calls.get(2).cb.onSuccess("manual");
        assertEquals(TranscriptEngine.Status.DONE, engine.get(3L).status);

        calls.get(0).cb.onSuccess("1");
        assertEquals(3, calls.size());

        engine.enqueueAuto(4L);
        assertEquals(4, calls.size());
        assertEquals(4L, calls.get(3).uid);
        engine.enqueueAuto(5L);
        assertEquals(4, calls.size());

        calls.get(1).cb.onSuccess("2");
        assertEquals(5, calls.size());
        assertEquals(5L, calls.get(4).uid);
    }

    @Test
    public void turningAutoOffClearsQueueButInflightFinishes() {
        engine.setAutoTranscribe(true);
        engine.enqueueAuto(1L);
        engine.enqueueAuto(2L);
        engine.enqueueAuto(3L);
        assertEquals(2, calls.size());

        engine.setAutoTranscribe(false);
        assertFalse(savedAuto);

        calls.get(0).cb.onSuccess("1");
        calls.get(1).cb.onSuccess("2");

        assertEquals(TranscriptEngine.Status.DONE, engine.get(1L).status);
        assertEquals(TranscriptEngine.Status.DONE, engine.get(2L).status);
        assertNull(engine.get(3L));
        assertEquals(2, calls.size());
    }
}
