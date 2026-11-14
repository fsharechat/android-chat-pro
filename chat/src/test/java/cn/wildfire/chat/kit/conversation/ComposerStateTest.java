package cn.wildfire.chat.kit.conversation;
import org.junit.Test;
import static org.junit.Assert.*;
public class ComposerStateTest {
    @Test public void emojiAndKeyboardLeaveVoiceMode() {
        ComposerState state = new ComposerState();
        state.enterVoice(); state.show(ComposerState.Surface.EMOJI); assertFalse(state.isVoice());
        state.enterVoice(); state.show(ComposerState.Surface.KEYBOARD); assertFalse(state.isVoice());
    }
    @Test public void moreAndCollapsePreserveVoiceMode() {
        ComposerState state = new ComposerState();
        state.enterVoice(); state.show(ComposerState.Surface.MORE); assertTrue(state.isVoice());
        state.collapse(); assertEquals(ComposerState.Surface.COLLAPSED, state.surface()); assertTrue(state.isVoice());
    }
    @Test public void aLaterRequestReplacesThePreviousSurface() {
        ComposerState state = new ComposerState();
        state.show(ComposerState.Surface.MORE); state.show(ComposerState.Surface.EMOJI);
        assertEquals(ComposerState.Surface.EMOJI, state.surface());
        state.collapse(); assertEquals(ComposerState.Surface.COLLAPSED, state.surface());
    }
}
