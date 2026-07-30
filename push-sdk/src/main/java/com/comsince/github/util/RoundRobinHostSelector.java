package com.comsince.github.util;

import java.util.Arrays;
import java.util.List;

public class RoundRobinHostSelector {
    private final List<String> hosts;
    private int index = 0;
    private volatile String currentHost;

    public RoundRobinHostSelector(String hostsStr) {
        if (hostsStr == null || hostsStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Hosts string cannot be empty.");
        }
        this.hosts = Arrays.asList(hostsStr.split(":"));
    }

    public synchronized String getNextHost() {
        String host = hosts.get(index);
        index = (index + 1) % hosts.size();
        currentHost = host;
        return host;
    }

    /**
     * 最近一次 {@link #getNextHost()} 选出的 host，首次选择前返回 null。
     * 仅用于展示，不参与选择逻辑。
     */
    public String getCurrentHost() {
        return currentHost;
    }
}
