package cn.wildfire.chat.kit.net;

import java.util.List;

/**
 * 节点表与「传给 ChatManager 的 host 串」之间的纯映射。
 *
 * 固定单个节点时只传该 host，push-sdk 的 RoundRobinHostSelector 拿到单 host 会恒定返回它，
 * 固定语义因此无需改动选择算法；未固定时传全部 host，恢复轮询。
 */
public final class ServerHostSpec {

    public static final String SEPARATOR = ":";

    private ServerHostSpec() {
    }

    /** 把全部节点拼成轮询用的 host 串。 */
    public static String joinAll(List<ServerNode> nodes) {
        StringBuilder builder = new StringBuilder();
        for (ServerNode node : nodes) {
            if (builder.length() > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(node.host);
        }
        return builder.toString();
    }

    /**
     * @param pinnedHost 用户固定的 host，null / 空白表示自动轮询
     * @return 固定且该 host 仍在节点表中时返回该 host，否则返回全部 host 的拼接串
     */
    public static String resolve(List<ServerNode> nodes, String pinnedHost) {
        if (pinnedHost == null || pinnedHost.trim().isEmpty()) {
            return joinAll(nodes);
        }
        if (findNode(nodes, pinnedHost) == null) {
            // 固定的节点已从节点表移除（如版本升级后集群调整），降级为自动
            return joinAll(nodes);
        }
        return pinnedHost;
    }

    /** 按 host 查节点，找不到返回 null。 */
    public static ServerNode findNode(List<ServerNode> nodes, String host) {
        if (host == null) {
            return null;
        }
        for (ServerNode node : nodes) {
            if (node.host.equals(host)) {
                return node;
            }
        }
        return null;
    }
}
