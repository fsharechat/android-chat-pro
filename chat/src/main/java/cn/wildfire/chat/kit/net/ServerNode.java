package cn.wildfire.chat.kit.net;

/**
 * 一个 IM 集群节点：展示名 + 连接 host。
 * 刻意不依赖任何 Android 类，以便纯 JVM 单测直接使用。
 */
public final class ServerNode {
    public final String name;
    public final String host;

    public ServerNode(String name, String host) {
        this.name = name;
        this.host = host;
    }
}
