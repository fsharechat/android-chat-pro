package cn.wildfire.chat.kit.audio;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecordingGestureTest {
    @Test public void cancelAndLateReleaseNeverSend() {
        RecordingGesture gesture = new RecordingGesture(); gesture.begin();
        assertFalse(gesture.finish(true)); assertFalse(gesture.finish(false));
    }
    @Test public void normalReleaseOrTimeoutCanOnlySendOnce() {
        RecordingGesture gesture = new RecordingGesture(); gesture.begin();
        assertTrue(gesture.finish(false)); assertFalse(gesture.finish(false));
    }
    @Test public void cancellationHasHysteresis() {
        RecordingGesture gesture = new RecordingGesture(); gesture.begin();
        gesture.move(65); gesture.move(50); assertTrue(gesture.cancelArmed()); assertFalse(gesture.finish(false));
    }
    @Test public void slidingBackRestoresSendIntent() {
        RecordingGesture gesture = new RecordingGesture(); gesture.begin();
        gesture.move(65); gesture.move(43); assertFalse(gesture.cancelArmed()); assertTrue(gesture.finish(false));
    }
}
