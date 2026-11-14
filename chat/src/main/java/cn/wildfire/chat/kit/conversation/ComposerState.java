package cn.wildfire.chat.kit.conversation;

/** UI intent, independent of IME callback order or temporary view visibility. */
public final class ComposerState {
    public enum Surface { COLLAPSED, KEYBOARD, EMOJI, MORE }
    private Surface surface = Surface.COLLAPSED;
    private boolean voice;
    public Surface surface() { return surface; }
    public boolean isVoice() { return voice; }
    public void show(Surface next) {
        if (next == Surface.KEYBOARD || next == Surface.EMOJI) voice = false;
        surface = next;
    }
    public void enterVoice() { voice = true; surface = Surface.COLLAPSED; }
    public void textMode() { voice = false; }
    public void collapse() { surface = Surface.COLLAPSED; }
}
