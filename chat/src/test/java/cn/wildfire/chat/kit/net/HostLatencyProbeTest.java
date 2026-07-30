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
        // 192.0.2.0/24 是 RFC 5737 保留的 TEST-NET-1，标准环境下不可路由；
        // 但本机 JVM 会自动经本地代理转发出站连接（见 all_proxy 环境变量），
        // 导致较大的 timeoutMs 下该地址反而会"连接成功"。
        // 用极短的 1ms 超时代替依赖目标真的不可达：任何真实握手（含被代理转发的）
        // 都不可能在 1ms 内完成，因此这里必然触发 SocketTimeoutException 或连接异常。
        long latency = HostLatencyProbe.probeOnce("192.0.2.1", 6789, 1);
        assertTrue("expected timeout or unreachable but was " + latency,
                latency == HostLatencyProbe.RESULT_TIMEOUT
                        || latency == HostLatencyProbe.RESULT_UNREACHABLE);
    }
}
