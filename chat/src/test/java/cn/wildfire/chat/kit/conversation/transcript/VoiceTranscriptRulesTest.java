package cn.wildfire.chat.kit.conversation.transcript;

import org.junit.Test;

import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Entry;
import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VoiceTranscriptRulesTest {
    private static final Entry LOADING = new Entry(Status.LOADING, "", false);
    private static final Entry DONE = new Entry(Status.DONE, "hi", false);
    private static final Entry EMPTY = new Entry(Status.DONE, "", false);
    private static final Entry HIDDEN = new Entry(Status.DONE, "hi", true);
    private static final Entry ERROR = new Entry(Status.ERROR, "", false);

    @Test
    public void pillOnlyForIncomingUnplayedUntranscribedWithAutoOff() {
        assertTrue(VoiceTranscriptRules.showPill(true, false, 1L, null, false));
        assertFalse(VoiceTranscriptRules.showPill(true, false, 1L, null, true));
        assertFalse(VoiceTranscriptRules.showPill(true, true, 1L, null, false));
        assertFalse(VoiceTranscriptRules.showPill(false, false, 1L, null, false));
        assertFalse(VoiceTranscriptRules.showPill(true, false, 1L, LOADING, false));
        assertFalse(VoiceTranscriptRules.showPill(true, false, 0L, null, false));
    }

    @Test
    public void blockStates() {
        assertEquals(VoiceTranscriptRules.Block.NONE, VoiceTranscriptRules.block(null));
        assertEquals(VoiceTranscriptRules.Block.LOADING, VoiceTranscriptRules.block(LOADING));
        assertEquals(VoiceTranscriptRules.Block.TEXT, VoiceTranscriptRules.block(DONE));
        assertEquals(VoiceTranscriptRules.Block.EMPTY, VoiceTranscriptRules.block(EMPTY));
        assertEquals(VoiceTranscriptRules.Block.ERROR, VoiceTranscriptRules.block(ERROR));
        assertEquals(VoiceTranscriptRules.Block.NONE, VoiceTranscriptRules.block(HIDDEN));
    }

    @Test
    public void menuItemsAreMutuallyExclusive() {
        assertTrue(VoiceTranscriptRules.showTranscribeMenu(1L, null));
        assertTrue(VoiceTranscriptRules.showTranscribeMenu(1L, ERROR));
        assertTrue(VoiceTranscriptRules.showTranscribeMenu(1L, HIDDEN));
        assertFalse(VoiceTranscriptRules.showTranscribeMenu(1L, DONE));
        assertFalse(VoiceTranscriptRules.showTranscribeMenu(1L, LOADING));

        assertTrue(VoiceTranscriptRules.showHideMenu(1L, DONE));
        assertTrue(VoiceTranscriptRules.showHideMenu(1L, LOADING));
        assertFalse(VoiceTranscriptRules.showHideMenu(1L, null));
        assertFalse(VoiceTranscriptRules.showHideMenu(1L, HIDDEN));
    }

    @Test
    public void noMenuWithoutMessageUid() {
        assertFalse(VoiceTranscriptRules.showTranscribeMenu(0L, null));
        assertFalse(VoiceTranscriptRules.showHideMenu(0L, DONE));
    }

    @Test
    public void isRealtimeRejectsOldMessages() {
        long now = 1_000_000_000L;
        long delta = 500L; // 服务器比本机快 500ms
        assertTrue(VoiceTranscriptRules.isRealtime(now + delta - 1_000, delta, now));
        assertTrue(VoiceTranscriptRules.isRealtime(now + delta - VoiceTranscriptRules.REALTIME_WINDOW_MS, delta, now));
        assertFalse(VoiceTranscriptRules.isRealtime(now + delta - VoiceTranscriptRules.REALTIME_WINDOW_MS - 1, delta, now));
    }
}
