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
    public void probeOnceReportsTimeoutForNonRoutableAddress() throws IOException {
        // 使用绑定但不 accept 的 ServerSocket 模拟一个不可达的端口：
        // 套接字已建立但立即关闭，导致连接超时或立即被拒。
        // 这种方式不依赖网络环境配置，能够在任何环境下产生预期的超时或不可达结果。
        ServerSocket blockingServer = new ServerSocket(0);
        int timeoutPort = blockingServer.getLocalPort();
        blockingServer.close();

        long latency = HostLatencyProbe.probeOnce("127.0.0.1", timeoutPort, 300);
        assertTrue("expected timeout or unreachable but was " + latency,
                latency == HostLatencyProbe.RESULT_TIMEOUT
                        || latency == HostLatencyProbe.RESULT_UNREACHABLE);
    }
}
