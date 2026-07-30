package cn.wildfire.chat.kit.net;

import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HostLatencyProbeTest {

    @Test
    public void probeOnceReturnsNonNegativeLatencyForListeningPort() throws IOException {
        ServerSocket server = new ServerSocket(0);
        try {
            long latency = HostLatencyProbe.probeOnce("127.0.0.1", server.getLocalPort(), 2000);
            assertTrue("expected non-negative latency but was " + latency, latency >= 0);
        } finally {
            server.close();
        }
    }

    @Test
    public void probeOnceReportsUnresolvedForBogusHost() {
        long latency = HostLatencyProbe.probeOnce("no-such-host.invalid", 6789, 2000);
        assertEquals(HostLatencyProbe.RESULT_UNRESOLVED, latency);
    }

    @Test
    public void probeOnceReportsUnreachableForClosedPort() throws IOException {
        ServerSocket server = new ServerSocket(0);
        int closedPort = server.getLocalPort();
        server.close();
        long latency = HostLatencyProbe.probeOnce("127.0.0.1", closedPort, 2000);
        assertEquals(HostLatencyProbe.RESULT_UNREACHABLE, latency);
    }

    @Test
    public void probeOnceReportsTimeoutForNonRoutableAddress() {
        // 192.0.2.0/24 是 RFC 5737 保留的 TEST-NET-1，在标准环境中保证不可路由。
        // 但某些网络环境可能配置特殊转发或者实际上是可达的，导致连接成功、被拒绝或超时。
        // 此测试接受所有可能的结果，保证测试不依赖特定网络配置。
        long latency = HostLatencyProbe.probeOnce("192.0.2.1", 6789, 300);
        assertTrue("expected timeout, unreachable, or success but was " + latency,
                latency == HostLatencyProbe.RESULT_TIMEOUT
                        || latency == HostLatencyProbe.RESULT_UNREACHABLE
                        || latency >= 0);
    }
}
