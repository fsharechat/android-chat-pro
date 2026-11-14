package cn.wildfire.chat.kit.audio;

/** One gesture may finish at most once. Distances are dp, not physical pixels. */
public final class RecordingGesture {
    private boolean active, cancelArmed;
    public void begin() { active = true; cancelArmed = false; }
    public void move(float upwardDp) {
        if (!active) return;
        if (upwardDp >= 64) cancelArmed = true;
        else if (upwardDp < 44) cancelArmed = false;
    }
    public boolean active() { return active; }
    public boolean cancelArmed() { return cancelArmed; }
    public boolean finish(boolean cancelled) {
        boolean send = active && !cancelled && !cancelArmed;
        active = cancelArmed = false;
        return send;
    }
}
