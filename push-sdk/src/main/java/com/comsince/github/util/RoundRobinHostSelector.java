package com.comsince.github.util;

import java.util.Arrays;
import java.util.List;

public class RoundRobinHostSelector {
    private final List<String> hosts;
    private int index = 0;

    public RoundRobinHostSelector(String hostsStr) {
        if (hostsStr == null || hostsStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Hosts string cannot be empty.");
        }
        this.hosts = Arrays.asList(hostsStr.split(":"));
    }

    public synchronized String getNextHost() {
        String host = hosts.get(index);
        index = (index + 1) % hosts.size();
        return host;
    }

    public static void main(String[] args) {
        String hostsStr = "backend-tcp.fsharechat.cn:backend-tcp-s2.fsharechat.cn";
        RoundRobinHostSelector selector = new RoundRobinHostSelector(hostsStr);

        for (int i = 0; i < 15; i++) {
            System.out.println("选择的 host: " + selector.getNextHost());
        }
    }
}
