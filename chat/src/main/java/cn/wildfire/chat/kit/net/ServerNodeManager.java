package cn.wildfire.chat.kit.net;

import android.content.Context;
import android.content.SharedPreferences;

import cn.wildfire.chat.app.Config;

/**
 * 「用户固定了哪个节点」的唯一读写入口。纯逻辑在 {@link ServerHostSpec}，
 * 本类只负责 SharedPreferences 存取。
 */
public final class ServerNodeManager {

    private static final String KEY_PINNED_HOST = "pinned_im_host";

    private ServerNodeManager() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(Config.SP_NAME, Context.MODE_PRIVATE);
    }

    /** 用户固定的 host，未固定（自动轮询）时返回 null。 */
    public static String pinnedHost(Context context) {
        String host = preferences(context).getString(KEY_PINNED_HOST, null);
        if (host == null || host.trim().isEmpty()) {
            return null;
        }
        // 节点表变更后固定值可能失效，此时视为自动
        return ServerHostSpec.findNode(Config.IM_SERVER_NODES, host) == null ? null : host;
    }

    /** 传给 ChatManager 的 host 串：固定了返回单 host，否则返回全部 host。 */
    public static String currentHostSpec(Context context) {
        return ServerHostSpec.resolve(Config.IM_SERVER_NODES, pinnedHost(context));
    }

    public static void pin(Context context, String host) {
        preferences(context).edit().putString(KEY_PINNED_HOST, host).apply();
    }

    public static void unpin(Context context) {
        preferences(context).edit().remove(KEY_PINNED_HOST).apply();
    }

    /** 按 host 查节点表中的节点，找不到返回 null。 */
    public static ServerNode nodeOf(String host) {
        return ServerHostSpec.findNode(Config.IM_SERVER_NODES, host);
    }
}
