package cn.wildfire.chat.kit.net;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测「到 host + port 建立 TCP 连接要多久」。
 *
 * 刻意用与 IM 相同的 host 和端口，避免出现「ICMP 通但 IM 端口不通」的假绿灯。
 * 探测完立即关闭连接，不发送任何协议数据。
 */
public final class HostLatencyProbe {

    public static final int TIMEOUT_MS = 2000;

    /** 连接在超时时间内未建立。 */
    public static final long RESULT_TIMEOUT = -1L;
    /** 连接被拒绝或网络不可达。 */
    public static final long RESULT_UNREACHABLE = -2L;
    /** 域名无法解析。 */
    public static final long RESULT_UNRESOLVED = -3L;

    public interface Callback {
        /** 主线程回调。latencyMs >= 0 为耗时，否则为 RESULT_* 哨兵值。 */
        void onResult(String host, long latencyMs);
    }

    public interface Cancelable {
        void cancel();
    }

    private HostLatencyProbe() {
    }

    /**
     * 同步探测一个 host，阻塞调用线程，不得在主线程调用。
     */
    public static long probeOnce(String host, int port, int timeoutMs) {
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            return RESULT_UNRESOLVED;
        }
        Socket socket = new Socket();
        long start = System.currentTimeMillis();
        try {
            socket.connect(address, timeoutMs);
            return System.currentTimeMillis() - start;
        } catch (SocketTimeoutException e) {
            return RESULT_TIMEOUT;
        } catch (IOException e) {
            return RESULT_UNREACHABLE;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 探测用连接，关闭失败无需处理
            }
        }
    }

    /**
     * 并发探测多个 host，每个 host 出结果就回调一次（主线程）。
     * 调用方必须在界面销毁时 cancel，否则回调会打到已销毁的 View 上。
     */
    public static Cancelable probeAll(List<String> hosts, final int port, final Callback callback) {
        final List<String> targets = new ArrayList<>(hosts);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(targets.size(), 4)));

        for (final String host : targets) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (cancelled.get()) {
                        return;
                    }
                    final long latency = probeOnce(host, port, TIMEOUT_MS);
                    if (cancelled.get()) {
                        return;
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled.get()) {
                                callback.onResult(host, latency);
                            }
                        }
                    });
                }
            });
        }
        pool.shutdown();

        return new Cancelable() {
            @Override
            public void cancel() {
                cancelled.set(true);
                mainHandler.removeCallbacksAndMessages(null);
                pool.shutdownNow();
            }
        };
    }
}
