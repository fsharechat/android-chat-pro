package cn.wildfire.chat.kit.conversation.transcript;

import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Entry;
import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Status;

/** 语音气泡上转文字相关控件的显示规则，与 electron / web / ios 保持一致 */
public final class VoiceTranscriptRules {
    public static final long REALTIME_WINDOW_MS = 2 * 60 * 1000;

    public enum Block { NONE, LOADING, TEXT, EMPTY, ERROR }

    private VoiceTranscriptRules() {
    }

    public static boolean showPill(boolean incoming, boolean played, long uid, Entry e, boolean auto) {
        return incoming && !played && uid > 0 && e == null && !auto;
    }

    public static Block block(Entry e) {
        if (e == null || e.hidden) {
            return Block.NONE;
        }
        if (e.status == Status.LOADING) {
            return Block.LOADING;
        }
        if (e.status == Status.ERROR) {
            return Block.ERROR;
        }
        return e.text.isEmpty() ? Block.EMPTY : Block.TEXT;
    }

    private static boolean showing(Entry e) {
        return e != null && !e.hidden && e.status != Status.ERROR;
    }

    public static boolean showTranscribeMenu(long uid, Entry e) {
        return uid > 0 && !showing(e);
    }

    public static boolean showHideMenu(long uid, Entry e) {
        return uid > 0 && showing(e);
    }

    /** 离线期间积压的语音登录后不自动识别，只识别 2 分钟内到达的 */
    public static boolean isRealtime(long serverTime, long serverDelta, long now) {
        return now - (serverTime - serverDelta) <= REALTIME_WINDOW_MS;
    }
}
