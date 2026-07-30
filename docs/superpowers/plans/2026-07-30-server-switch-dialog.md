# 连接服务信息弹窗与节点切换 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 点击主界面顶栏「飞享IM」标题弹出连接服务信息弹窗，展示地址／端口／状态／延迟，并支持在预置集群节点间切换并固定。

**Architecture:** 纯逻辑（host spec 解析、TCP 建连探测）拆成不依赖 Android 的静态方法以便 JUnit 直测；SharedPreferences 读写与并发编排放在薄的 Android 包装层；跨 `:marsservice` 进程的节点切换与实连 host 上报各走一个新 AIDL 方法；节点固定复用 `RoundRobinHostSelector` 收到单 host 时恒定返回的既有行为，不改选择算法。

**Tech Stack:** Java 8 / Android minSdk 21 / compileSdk 29、JUnit 4.12、ButterKnife 7、androidx lifecycle 2.0.0-beta01、AIDL。

## Global Constraints

- **禁止 git commit。** 本计划任何步骤都不得执行 `git commit` / `git add`。所有改动留在工作区，由用户自行提交。每个任务以「跑测试 + 人工核对」收尾。
- JDK 11 构建；`compileSdkVersion 29`、`minSdkVersion 21`、`targetSdkVersion 29`。
- 所有颜色必须使用既有 token：`ink` / `paper` / `surface` / `mist` / `fog` / `leaf` / `coral` / `cobalt`。禁止在布局或代码中写死十六进制色值——`values-night/colors.xml` 与 `color-night/` 已有对应深色值，走 token 即自动适配夜间模式。
- 所有用户可见文案必须放入 `chat/src/main/res/values/strings.xml`，不得硬编码在布局或 Java 中。
- `Config` 类的静态初始化依赖 `android.os.Environment`，**纯 JUnit 测试中不可加载 `Config`**。需要单测的纯逻辑不得引用 `Config`。
- `WfcBaseActivity.toolbar` 字段是 package-private 且位于 `cn.wildfire.chat.kit` 包，`MainActivity`（`cn.wildfire.chat.app.main`）**无法访问**，必须 `findViewById(R.id.toolbar)`。
- R 类包名为 `cn.wildfirechat.chat.R`。
- 节点显示名与 host 的对应关系只在 `Config.IM_SERVER_NODES` 一处维护。
- 端口统一取 `Config.IM_SERVER_PORT`（6789）。
- 延迟指标统一为「到 host + port 的 TCP 建连耗时」，禁止混入应用层 PING RTT。

---

### Task 1: push-sdk 暴露当前选中的 host

**Files:**
- Modify: `push-sdk/src/main/java/com/comsince/github/util/RoundRobinHostSelector.java`
- Modify: `push-sdk/src/main/java/com/comsince/github/client/AndroidNIOClient.java`
- Test: `push-sdk/src/test/java/com/comsince/github/util/RoundRobinHostSelectorTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `RoundRobinHostSelector.getCurrentHost()` → `String`（首次 `getNextHost()` 前为 `null`）；`AndroidNIOClient.getCurrentHost()` → `String`

- [ ] **Step 1: 写失败测试**

创建 `push-sdk/src/test/java/com/comsince/github/util/RoundRobinHostSelectorTest.java`：

```java
package com.comsince.github.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RoundRobinHostSelectorTest {

    @Test
    public void currentHostIsNullBeforeFirstSelection() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("a.example.com:b.example.com");
        assertNull(selector.getCurrentHost());
    }

    @Test
    public void currentHostMatchesLastSelection() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("a.example.com:b.example.com");
        String first = selector.getNextHost();
        assertEquals(first, selector.getCurrentHost());
        String second = selector.getNextHost();
        assertEquals(second, selector.getCurrentHost());
    }

    @Test
    public void currentHostSurvivesWrapAround() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("a.example.com:b.example.com");
        selector.getNextHost();
        selector.getNextHost();
        String third = selector.getNextHost();
        assertEquals("a.example.com", third);
        assertEquals("a.example.com", selector.getCurrentHost());
    }

    @Test
    public void singleHostAlwaysReturnsSameHost() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("only.example.com");
        assertEquals("only.example.com", selector.getNextHost());
        assertEquals("only.example.com", selector.getNextHost());
        assertEquals("only.example.com", selector.getCurrentHost());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :push-sdk:test --tests '*RoundRobinHostSelectorTest*'
```

Expected: 编译失败，`cannot find symbol: method getCurrentHost()`

- [ ] **Step 3: 给 RoundRobinHostSelector 加 getCurrentHost**

在 `RoundRobinHostSelector` 中，字段区加一行，并在 `getNextHost()` 里记录：

```java
    private final List<String> hosts;
    private int index = 0;
    private volatile String currentHost;

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
```

同时删掉该类里的 `public static void main(String[] args)` 方法——它的职责已由本任务的单测覆盖。

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :push-sdk:test --tests '*RoundRobinHostSelectorTest*'
```

Expected: 4 个测试全部 PASS

- [ ] **Step 5: 给 AndroidNIOClient 加委托 getter**

在 `AndroidNIOClient` 中 `rebuildSelector()` 方法之后插入：

```java
    /**
     * 最近一次连接所选的 host，仅用于展示。连接失败或已 close 后仍返回最后一次选择值，
     * 是否真正连通由调用方结合 {@link #connectStatus} 判断。
     */
    public String getCurrentHost() {
        return roundRobinHostSelector.getCurrentHost();
    }
```

不新增字段——`roundRobinHostSelector` 已持有该状态，重复记录会引入不一致。

- [ ] **Step 6: 确认整个 push-sdk 仍可编译**

```bash
./gradlew :push-sdk:assembleDebug
```

Expected: BUILD SUCCESSFUL

---

### Task 2: 节点表与 host spec 纯解析逻辑

**Files:**
- Create: `chat/src/main/java/cn/wildfire/chat/kit/net/ServerNode.java`
- Create: `chat/src/main/java/cn/wildfire/chat/kit/net/ServerHostSpec.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/app/Config.java`
- Modify: `chat/build.gradle:89-128`（dependencies 块）
- Test: `chat/src/test/java/cn/wildfire/chat/kit/net/ServerHostSpecTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ServerNode`：`public final String name`、`public final String host`，构造器 `ServerNode(String name, String host)`
  - `ServerHostSpec.SEPARATOR` = `":"`
  - `ServerHostSpec.joinAll(List<ServerNode> nodes)` → `String`
  - `ServerHostSpec.resolve(List<ServerNode> nodes, String pinnedHost)` → `String`
  - `ServerHostSpec.findNode(List<ServerNode> nodes, String host)` → `ServerNode`（找不到返回 `null`）
  - `Config.IM_SERVER_NODES` → `List<ServerNode>`（不可变，顺序：上海节点／主节点／备节点 S2）
  - `Config.IM_SERVER_HOST` 仍为 `public static String`，值由 `IM_SERVER_NODES` 派生

`ServerNode` 独立成文件而非做 `Config` 的嵌套类，是因为 `Config` 的静态初始化会调用 `android.os.Environment`，纯 JUnit 测试一加载 `Config` 就抛 `RuntimeException: Stub!`。

- [ ] **Step 1: 给 chat 模块加 JUnit 依赖**

`chat/build.gradle` 的 `dependencies { ... }` 块内，在 `implementation project(':imagepicker')` 之后加一行：

```groovy
    testImplementation 'junit:junit:4.12'
```

- [ ] **Step 2: 写失败测试**

创建 `chat/src/test/java/cn/wildfire/chat/kit/net/ServerHostSpecTest.java`：

```java
package cn.wildfire.chat.kit.net;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ServerHostSpecTest {

    private static final ServerNode SH = new ServerNode("上海节点", "sh.example.com");
    private static final ServerNode MAIN = new ServerNode("主节点", "main.example.com");
    private static final ServerNode S2 = new ServerNode("备节点 S2", "s2.example.com");

    private List<ServerNode> nodes() {
        return Arrays.asList(SH, MAIN, S2);
    }

    @Test
    public void joinAllUsesColonSeparatorInDeclaredOrder() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.joinAll(nodes()));
    }

    @Test
    public void joinAllOfSingleNodeHasNoSeparator() {
        assertEquals("sh.example.com", ServerHostSpec.joinAll(Collections.singletonList(SH)));
    }

    @Test
    public void resolveWithoutPinReturnsAllHosts() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.resolve(nodes(), null));
    }

    @Test
    public void resolveWithBlankPinReturnsAllHosts() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.resolve(nodes(), "   "));
    }

    @Test
    public void resolveWithPinReturnsThatHostOnly() {
        assertEquals("main.example.com", ServerHostSpec.resolve(nodes(), "main.example.com"));
    }

    @Test
    public void resolveWithUnknownPinFallsBackToAllHosts() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.resolve(nodes(), "gone.example.com"));
    }

    @Test
    public void findNodeReturnsMatchingNode() {
        assertSame(S2, ServerHostSpec.findNode(nodes(), "s2.example.com"));
    }

    @Test
    public void findNodeReturnsNullForUnknownHost() {
        assertNull(ServerHostSpec.findNode(nodes(), "gone.example.com"));
    }

    @Test
    public void findNodeReturnsNullForNullHost() {
        assertNull(ServerHostSpec.findNode(nodes(), null));
    }

    @Test
    public void joinAllOfEmptyListReturnsEmptyString() {
        assertEquals("", ServerHostSpec.joinAll(new ArrayList<ServerNode>()));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
./gradlew :chat:testDebugUnitTest --tests '*ServerHostSpecTest*'
```

Expected: 编译失败，`package cn.wildfire.chat.kit.net does not exist`

- [ ] **Step 4: 写 ServerNode**

创建 `chat/src/main/java/cn/wildfire/chat/kit/net/ServerNode.java`：

```java
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
```

- [ ] **Step 5: 写 ServerHostSpec**

创建 `chat/src/main/java/cn/wildfire/chat/kit/net/ServerHostSpec.java`：

```java
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
```

- [ ] **Step 6: 跑测试确认通过**

```bash
./gradlew :chat:testDebugUnitTest --tests '*ServerHostSpecTest*'
```

Expected: 10 个测试全部 PASS

- [ ] **Step 7: 让 Config 用节点表派生 IM_SERVER_HOST**

修改 `chat/src/main/java/cn/wildfire/chat/app/Config.java`。加 import：

```java
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cn.wildfire.chat.kit.net.ServerHostSpec;
import cn.wildfire.chat.kit.net.ServerNode;
```

把现有这三行：

```java
//    String IM_SERVER_HOST = "192.168.0.102";
    public static String IM_SERVER_HOST = "sh-backend-tcp.fsharechat.cn:backend-tcp.fsharechat.cn:backend-tcp-s2.fsharechat.cn";
//    String IM_SERVER_HOST = "172.16.46.201";
```

替换为（`IM_SERVER_NODES` 必须声明在 `IM_SERVER_HOST` 之前，静态初始化按声明顺序执行）：

```java
    /** IM 集群节点表：展示名与 host 的唯一维护点，顺序即弹窗列表顺序。 */
    public static final List<ServerNode> IM_SERVER_NODES = Collections.unmodifiableList(Arrays.asList(
            new ServerNode("上海节点", "sh-backend-tcp.fsharechat.cn"),
            new ServerNode("主节点", "backend-tcp.fsharechat.cn"),
            new ServerNode("备节点 S2", "backend-tcp-s2.fsharechat.cn")));

    /** 全部节点拼接串，即默认的轮询地址。实际连接地址请走 ServerNodeManager.currentHostSpec()。 */
    public static String IM_SERVER_HOST = ServerHostSpec.joinAll(IM_SERVER_NODES);
```

- [ ] **Step 8: 确认编译通过且关于页未被破坏**

```bash
./gradlew :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL。人工核对 `chat/src/main/java/cn/wildfire/chat/kit/setting/AboutActivity.java:31` 仍引用 `Config.IM_SERVER_HOST`，无需改动。

---

### Task 3: 固定节点的持久化

**Files:**
- Create: `chat/src/main/java/cn/wildfire/chat/kit/net/ServerNodeManager.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/WfcUIKit.java:105`

**Interfaces:**
- Consumes: `ServerHostSpec.resolve` / `ServerHostSpec.findNode`、`Config.IM_SERVER_NODES`、`Config.SP_NAME`
- Produces:
  - `ServerNodeManager.currentHostSpec(Context)` → `String`
  - `ServerNodeManager.pinnedHost(Context)` → `String`（`null` = 自动）
  - `ServerNodeManager.pin(Context, String host)` → `void`
  - `ServerNodeManager.unpin(Context)` → `void`
  - `ServerNodeManager.nodeOf(String host)` → `ServerNode`

- [ ] **Step 1: 写 ServerNodeManager**

创建 `chat/src/main/java/cn/wildfire/chat/kit/net/ServerNodeManager.java`：

```java
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
```

- [ ] **Step 2: 让启动时使用持久化的 host spec**

修改 `chat/src/main/java/cn/wildfire/chat/kit/WfcUIKit.java`，把 `initWFClient` 中的：

```java
        ChatManager.init(application, Config.IM_SERVER_HOST, Config.IM_SERVER_PORT);
```

替换为：

```java
        String imHostSpec = ServerNodeManager.currentHostSpec(application);
        Log.i("WfcUIKit", "im host spec=" + imHostSpec);
        ChatManager.init(application, imHostSpec, Config.IM_SERVER_PORT);
```

并补 import：

```java
import android.util.Log;

import cn.wildfire.chat.kit.net.ServerNodeManager;
```

若文件中已存在 `android.util.Log` 或同名 import，跳过重复项。

- [ ] **Step 3: 构建并安装**

```bash
./gradlew :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 人工核对默认值**

安装到真机后启动 App，抓日志：

```bash
adb logcat -d | grep "im host spec"
```

Expected: 输出全部三个 host 的拼接串 `sh-backend-tcp.fsharechat.cn:backend-tcp.fsharechat.cn:backend-tcp-s2.fsharechat.cn`（尚无固定值，走自动）。

---

### Task 4: TCP 建连耗时探测

**Files:**
- Create: `chat/src/main/java/cn/wildfire/chat/kit/net/HostLatencyProbe.java`
- Test: `chat/src/test/java/cn/wildfire/chat/kit/net/HostLatencyProbeTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `HostLatencyProbe.TIMEOUT_MS` = `2000`（int）
  - `HostLatencyProbe.RESULT_TIMEOUT` = `-1L`
  - `HostLatencyProbe.RESULT_UNREACHABLE` = `-2L`
  - `HostLatencyProbe.RESULT_UNRESOLVED` = `-3L`
  - `HostLatencyProbe.probeOnce(String host, int port, int timeoutMs)` → `long`（>= 0 为耗时毫秒，否则为上述哨兵值）
  - `HostLatencyProbe.probeAll(List<String> hosts, int port, Callback callback)` → `HostLatencyProbe.Cancelable`
  - `interface HostLatencyProbe.Callback { void onResult(String host, long latencyMs); }`（主线程回调）
  - `interface HostLatencyProbe.Cancelable { void cancel(); }`

- [ ] **Step 1: 写失败测试**

创建 `chat/src/test/java/cn/wildfire/chat/kit/net/HostLatencyProbeTest.java`。只测纯静态的 `probeOnce`——`probeAll` 依赖 `android.os.Looper`，不在纯 JVM 测试范围内，由 Task 9 的真机验收覆盖。

```java
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
        // 192.0.2.0/24 是 RFC 5737 保留的 TEST-NET-1，保证不可路由，连接会挂到超时
        long latency = HostLatencyProbe.probeOnce("192.0.2.1", 6789, 300);
        assertEquals(HostLatencyProbe.RESULT_TIMEOUT, latency);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :chat:testDebugUnitTest --tests '*HostLatencyProbeTest*'
```

Expected: 编译失败，`cannot find symbol: class HostLatencyProbe`

- [ ] **Step 3: 写 HostLatencyProbe**

创建 `chat/src/main/java/cn/wildfire/chat/kit/net/HostLatencyProbe.java`：

```java
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :chat:testDebugUnitTest --tests '*HostLatencyProbeTest*'
```

Expected: 4 个测试全部 PASS。若 `probeOnceReportsTimeoutForNonRoutableAddress` 在某些网络环境下返回 `RESULT_UNREACHABLE`（网关立即回 ICMP unreachable 而非静默丢包），说明该环境无法产生超时场景——此时把该断言改为 `assertTrue(latency == RESULT_TIMEOUT || latency == RESULT_UNREACHABLE)` 并在测试方法上加注释说明原因，不要删除测试。

- [ ] **Step 5: 确认全量单测通过**

```bash
./gradlew :chat:testDebugUnitTest :push-sdk:test
```

Expected: BUILD SUCCESSFUL，Task 1／2／4 的测试全部通过

---

### Task 5: 跨进程上报实际连接的 host

**Files:**
- Modify: `client/src/main/aidl/cn/wildfirechat/client/IRemoteClient.aidl:58`
- Modify: `client/src/main/java/cn/wildfirechat/proto/AbstractProtoService.java`
- Modify: `client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java:303-311`
- Modify: `client/src/main/java/cn/wildfirechat/client/ClientService.java:259-262`
- Modify: `client/src/main/java/cn/wildfirechat/remote/ChatManager.java`

**Interfaces:**
- Consumes: `AndroidNIOClient.getCurrentHost()`（Task 1）
- Produces:
  - `AbstractProtoService.getConnectedHost()` → `String`（未连接返回 `null`）
  - `JavaProtoLogic.getConnectedHost()` → `String`
  - AIDL `String IRemoteClient.getConnectedHost()`（**同步方法，不能加 oneway，需要返回值**）
  - `ChatManager.getConnectedHost()` → `String`（未 bind 或 RemoteException 时返回 `null`）

自动轮询模式下究竟连到哪个 host，只有 `:marsservice` 进程知道。这条只读通道让弹窗能显示真实地址而不是「未知」。

- [ ] **Step 1: 给 AbstractProtoService 加 getConnectedHost**

在 `client/src/main/java/cn/wildfirechat/proto/AbstractProtoService.java` 中，`reconnect()` 方法之前插入：

```java
    /**
     * 当前真正连通的 host，未连接时返回 null。
     * 只反映连接状态，不反映用户固定了哪个节点。
     */
    public String getConnectedHost(){
        AndroidNIOClient client = androidNIOClient;
        if(client == null || client.connectStatus != ConnectStatus.CONNECTED){
            return null;
        }
        return client.getCurrentHost();
    }
```

补 import（`ConnectStatus` 与 `AndroidNIOClient` 同在 `com.comsince.github.client` 包，不会随通配 import 自动引入）：

```java
import com.comsince.github.client.ConnectStatus;
```

- [ ] **Step 2: 给 JavaProtoLogic 加静态转发**

在 `client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java` 的 `reconnect()` 方法之后插入：

```java
    public static String getConnectedHost(){
        return protoService == null ? null : protoService.getConnectedHost();
    }
```

- [ ] **Step 3: 加 AIDL 方法**

在 `client/src/main/aidl/cn/wildfirechat/client/IRemoteClient.aidl` 中，`oneway void setServerAddress(in String host, in int port);` 之后加一行：

```
    String getConnectedHost();
```

不能标 `oneway`——`oneway` 方法不允许有返回值。

- [ ] **Step 4: 在 ClientService 实现**

在 `client/src/main/java/cn/wildfirechat/client/ClientService.java` 的 `setServerAddress` 方法之后插入：

```java
        @Override
        public String getConnectedHost() throws RemoteException {
            return JavaProtoLogic.getConnectedHost();
        }
```

- [ ] **Step 5: 在 ChatManager 加包装**

在 `client/src/main/java/cn/wildfirechat/remote/ChatManager.java` 中 `disconnect(boolean cleanSession)` 方法之后插入：

```java
    /**
     * 当前真正连通的 host，未连接／服务未 bind 时返回 null。
     * 这是同步跨进程调用，只在弹窗打开与连接状态变化时调用，勿放入高频路径。
     */
    public String getConnectedHost() {
        if (mClient == null) {
            return null;
        }
        try {
            return mClient.getConnectedHost();
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
    }
```

- [ ] **Step 6: 构建，确认 AIDL 重新生成**

```bash
./gradlew :client:assembleDebug :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL。若报 `getConnectedHost() is not abstract and does not override`，说明 AIDL 桩未重新生成，执行 `./gradlew :client:clean :client:assembleDebug`。

- [ ] **Step 7: 人工核对**

本任务无法单测（跨进程 + 真实连接）。装机后在 `MainActivity.afterViews()` 末尾临时插入一行：

```java
        Log.i("MainActivity", "connected host=" + ChatManagerHolder.gChatManager.getConnectedHost());
```

登录并等待顶栏显示「飞享IM」（已连接）后，切后台再切前台触发一次，抓日志：

```bash
adb logcat -d | grep "connected host"
```

Expected: 打印三个节点之一的真实 host（非 null、非拼接串）。核对完**删除这行临时日志**。

---

### Task 6: 运行时切换节点且不产生重复连接

**Files:**
- Modify: `client/src/main/java/cn/wildfirechat/proto/AbstractProtoService.java:189-215`（`reconnect()`）
- Modify: `client/src/main/java/cn/wildfirechat/proto/AbstractProtoService.java`（新增 `switchServer`）
- Modify: `client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java`
- Modify: `client/src/main/aidl/cn/wildfirechat/client/IRemoteClient.aidl`
- Modify: `client/src/main/java/cn/wildfirechat/client/ClientService.java`
- Modify: `client/src/main/java/cn/wildfirechat/remote/ChatManager.java`

**Interfaces:**
- Consumes: `AbstractProtoService.connect(String, int)`、`cancelHeartTimer()`、`cancelReconnectTimer()`、`clearSendMessageTimerMap()`（均为既有方法）
- Produces:
  - `AbstractProtoService.switchServer(String host, int port)` → `void`
  - `JavaProtoLogic.switchServer(String host, int port)` → `void`
  - AIDL `oneway void IRemoteClient.switchServerAddress(in String host, in int port)`
  - `ChatManager.switchServerHost(String hostSpec)` → `void`

**为什么不能复用 `disconnect` + `connect`：** `AbstractProtoService.disConnect()`（`:551`）会 `imMemoryStore.stop()` 并把 `userDisconnect = true` **永久堵死后续 reconnect**，它是登出路径（`SettingActivity.java:22`）。走它会拆掉会话与本地消息库并要求重新登录。

- [ ] **Step 1: 修 reconnect() 的 null 判断位置**

`reconnect()` 现在在方法外判 `androidNIOClient != null`，但 runnable 内部又重新读该字段。Task 6 引入的置空操作会让这个既有缺陷变成可达的 NPE。把 `client/src/main/java/cn/wildfirechat/proto/AbstractProtoService.java` 的 `reconnect()` 整体替换为：

```java
    public void reconnect(){
        final AndroidNIOClient client = androidNIOClient;
        if(!userDisconnect && client != null){
            client.post(new Runnable() {
                @Override
                public void run() {
                    AndroidNIOClient current = androidNIOClient;
                    if(current == null){
                        log.i("reconnect skipped, client already released");
                        return;
                    }
                    log.i("reconnect status "+current.connectStatus);
                    switch (current.connectStatus){
                        case CONNECTING:
                            break;
                        case CONNECTED:
                            clearSendMessageTimerMap();
                            cancelReconnectTimer();
                            cancelHeartTimer();
                            current.close();
                            JavaProtoLogic.onConnectionStatusChanged(ConnectionStatusConnecting);
                            current.connect();
                            break;
                        case DISCONNECT:
                            clearSendMessageTimerMap();
                            cancelReconnectTimer();
                            cancelHeartTimer();
                            JavaProtoLogic.onConnectionStatusChanged(ConnectionStatusConnecting);
                            current.connect();
                            break;
                    }
                }
            });
        }
    }
```

- [ ] **Step 2: 加 switchServer**

在 `AbstractProtoService` 的 `reconnect()` 之后插入：

```java
    /**
     * 切换到新的 host 并重连，保留会话与本地消息库。
     *
     * 顺序是刻意的，三个竞态都靠它规避：
     * 1) 整个流程经 AndroidNIOClient.post() 提交。该执行器是 static 且核心线程数 1 配无界队列
     *    （永不扩容），因此与队列中积压的 reconnect runnable 严格串行，不会一新一旧同时连。
     * 2) close() 前先摘 pushMessageCallback：否则 60s 连接超时 runnable 会走 receiveException(),
     *    在 reconnectNum <= 3 时排一次重连——那次会连回旧 host。
     * 3) 先 cancelReconnectTimer()，清掉 alarmWrapper 上待触发的重连。
     *
     * AsyncServer.stop() 在非 selector 线程调用时会阻塞到关闭完成，所以本方法不能在主线程执行。
     */
    public void switchServer(String host, int port){
        final AndroidNIOClient oldClient = androidNIOClient;
        if(oldClient == null){
            log.i("switchServer without existing client, connect directly host="+host);
            connect(host, port);
            return;
        }
        final String targetHost = host;
        final int targetPort = port;
        oldClient.post(new Runnable() {
            @Override
            public void run() {
                log.i("switchServer to host="+targetHost+" port="+targetPort);
                cancelHeartTimer();
                cancelReconnectTimer();
                clearSendMessageTimerMap();
                oldClient.setPushMessageCallback(null);
                oldClient.close();
                androidNIOClient = null;
                reconnectNum = 0;
                JavaProtoLogic.onConnectionStatusChanged(ConnectionStatusConnecting);
                connect(targetHost, targetPort);
            }
        });
    }
```

- [ ] **Step 3: 加 JavaProtoLogic 转发**

在 `JavaProtoLogic` 的 `connect(String host, int shortPort)` 方法之后插入：

```java
    public static void switchServer(String host, int shortPort){
        logger.i("switchServer host "+host+" port "+shortPort);
        if(protoService != null){
            protoService.switchServer(host, shortPort);
        }
    }
```

- [ ] **Step 4: 加 AIDL 方法**

在 `IRemoteClient.aidl` 中 `String getConnectedHost();` 之后加一行：

```
    oneway void switchServerAddress(in String host, in int port);
```

- [ ] **Step 5: 在 ClientService 实现**

在 `ClientService` 的 `getConnectedHost()` 之后插入：

```java
        @Override
        public void switchServerAddress(String host, int port) throws RemoteException {
            logger.i("switchServerAddress host "+host+" port "+port);
            mHost = host;
            mPort = port;
            JavaProtoLogic.switchServer(host, port);
        }
```

- [ ] **Step 6: 在 ChatManager 加包装**

在 `ChatManager` 的 `getConnectedHost()` 之后插入：

```java
    /**
     * 切换 IM 连接地址并立即重连。hostSpec 为单个 host（固定节点）或 ":" 分隔的多 host（自动轮询）。
     * 同步更新 SERVER_HOST，保证服务重新 bind 时 setServerAddress 用的是新值。
     */
    public void switchServerHost(String hostSpec) {
        this.SERVER_HOST = hostSpec;
        if (mClient == null) {
            Log.e(TAG, "Chat service not binded");
            return;
        }
        try {
            mClient.switchServerAddress(hostSpec, SERVER_PORT);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
```

- [ ] **Step 7: 构建**

```bash
./gradlew :client:assembleDebug :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 人工核对切换不产生重复连接**

本任务无法单测（跨进程 + 真实 socket）。装机后在 `MainActivity.afterViews()` 末尾临时插入：

```java
        new Handler().postDelayed(() -> ChatManagerHolder.gChatManager
                .switchServerHost("backend-tcp-s2.fsharechat.cn"), 15000);
```

登录后等 15 秒触发切换，然后核对三项：

```bash
# 1) 只剩一条到新节点的连接，旧节点连接已消失
adb shell "netstat -tn | grep 6789"
# 2) 日志里 switchServer 之后只有一次 connect，且没有连回旧 host
adb logcat -d | grep -E "switchServer|reconnect|connect host"
# 3) 没有 AndroidNIOClient selector 线程残留
adb shell ps -T | grep -c AndroidNIOClient
```

Expected: (1) 仅一条 ESTABLISHED 指向 `backend-tcp-s2`；(2) 无「连回旧 host」记录；(3) 计数不随切换次数增长。核对完**删除这段临时代码**。

---

### Task 7: 弹窗资源

**Files:**
- Create: `chat/src/main/res/drawable/bg_dialog_card.xml`
- Create: `chat/src/main/res/drawable/bg_dialog_button_primary.xml`
- Create: `chat/src/main/res/drawable/bg_dialog_button_secondary.xml`
- Create: `chat/src/main/res/drawable/bg_status_dot.xml`
- Create: `chat/src/main/res/layout/server_info_dialog.xml`
- Create: `chat/src/main/res/layout/server_info_row.xml`
- Create: `chat/src/main/res/layout/server_node_item.xml`
- Modify: `chat/src/main/res/values/strings.xml`
- Modify: `chat/src/main/res/values/styles.xml`

**Interfaces:**
- Consumes: 既有颜色 token
- Produces: 上述资源 id，供 Task 8／9 的 Java 代码引用。关键 id：`dialogTitle`、`statusDot`、`infoContainer`、`nodeContainer`、`nodeListTip`、`secondaryButton`、`primaryButton`；行布局中的 `rowLabel`、`rowValue`；节点项中的 `nodeRadio`、`nodeName`、`nodeHost`、`nodeLatency`、`nodeProgress`

按钮采用内缩胶囊样式（12dp 外边距 + 10dp 圆角），而非贴卡片边缘通铺。原因：卡片是 16dp 圆角，通铺按钮需要为左下／右下角单独做圆角 drawable，且 Android 不会把子 View 裁剪到父容器圆角内，实现代价与出错概率都高于收益。视觉上与 App 既有的 `bg_surface_card`（16dp）一致。

- [ ] **Step 1: 建 drawable**

`chat/src/main/res/drawable/bg_dialog_card.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="16dp" />
</shape>
```

`chat/src/main/res/drawable/bg_dialog_button_primary.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="@color/cobalt_pressed">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/cobalt" />
            <corners android:radius="10dp" />
        </shape>
    </item>
</ripple>
```

`chat/src/main/res/drawable/bg_dialog_button_secondary.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="@color/fog">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/paper" />
            <corners android:radius="10dp" />
        </shape>
    </item>
</ripple>
```

`chat/src/main/res/drawable/bg_status_dot.xml`（颜色在代码里用 `setBackgroundTintList` 覆盖，此处只定形状与占位色）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/mist" />
</shape>
```

- [ ] **Step 2: 加文案**

在 `chat/src/main/res/values/strings.xml` 的 `</resources>` 之前插入：

```xml
    <!--连接服务弹窗-->
    <string name="server_info_title">当前连接服务</string>
    <string name="server_select_title">选择连接服务</string>
    <string name="server_select_tip">建连耗时</string>
    <string name="server_info_name_label">服务名称</string>
    <string name="server_info_name_value">飞享IM 消息服务</string>
    <string name="server_info_host_label">连接地址</string>
    <string name="server_info_port_label">端口</string>
    <string name="server_info_status_label">连接状态</string>
    <string name="server_info_latency_label">延迟</string>
    <string name="server_info_switch">切换连接地址</string>
    <string name="server_info_close">关闭</string>
    <string name="server_select_back">返回</string>
    <string name="server_select_confirm">确定</string>
    <string name="server_node_auto">自动（集群轮询）</string>
    <string name="server_node_auto_host">按集群顺序轮询</string>
    <string name="server_status_connected">已连接</string>
    <string name="server_status_connecting">正在连接…</string>
    <string name="server_status_unconnected">未连接</string>
    <string name="server_latency_unknown">——</string>
    <string name="server_latency_ms">%1$dms</string>
    <string name="server_latency_timeout">超时</string>
    <string name="server_latency_unreachable">不可达</string>
    <string name="server_latency_unresolved">无法解析</string>
```

- [ ] **Step 3: 加样式**

在 `chat/src/main/res/values/styles.xml` 的 `</resources>` 之前插入：

```xml
    <!--连接服务弹窗：无标题栏、透明窗口背景，圆角由卡片 drawable 提供-->
    <style name="ServerInfoDialog" parent="Theme.AppCompat.Dialog">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:backgroundDimEnabled">true</item>
    </style>
```

- [ ] **Step 4: 建信息行布局**

`chat/src/main/res/layout/server_info_row.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="48dp"
    android:orientation="horizontal">

    <TextView
        android:id="@+id/rowLabel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textColor="@color/mist"
        android:textSize="15sp"
        tools:text="连接地址" />

    <TextView
        android:id="@+id/rowValue"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="16dp"
        android:gravity="end"
        android:textColor="@color/ink"
        android:textSize="15sp"
        tools:text="im-test.feixiang.io" />
</LinearLayout>
```

注意根标签需要声明 `xmlns:tools="http://schemas.android.com/tools"` 才能用 `tools:text`；若不想引入 tools 命名空间，删掉两处 `tools:text` 即可。

- [ ] **Step 5: 建节点项布局**

`chat/src/main/res/layout/server_node_item.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?android:attr/selectableItemBackground"
    android:gravity="center_vertical"
    android:minHeight="56dp"
    android:orientation="horizontal"
    android:paddingTop="6dp"
    android:paddingBottom="6dp">

    <RadioButton
        android:id="@+id/nodeRadio"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:clickable="false"
        android:focusable="false" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="8dp"
        android:orientation="vertical">

        <TextView
            android:id="@+id/nodeName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="@color/ink"
            android:textSize="15sp" />

        <TextView
            android:id="@+id/nodeHost"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="@color/mist"
            android:textSize="12sp" />
    </LinearLayout>

    <ProgressBar
        android:id="@+id/nodeProgress"
        android:layout_width="16dp"
        android:layout_height="16dp"
        android:layout_marginStart="8dp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/nodeLatency"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:textColor="@color/mist"
        android:textSize="13sp" />
</LinearLayout>
```

- [ ] **Step 6: 建弹窗主布局**

`chat/src/main/res/layout/server_info_dialog.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_dialog_card"
    android:orientation="vertical">

    <!--标题行：标题 + 连接状态色块-->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="20dp"
        android:paddingTop="20dp"
        android:paddingEnd="20dp"
        android:paddingBottom="16dp">

        <TextView
            android:id="@+id/dialogTitle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/server_info_title"
            android:textColor="@color/ink"
            android:textSize="18sp"
            android:fontFamily="sans-serif-medium" />

        <TextView
            android:id="@+id/nodeListTip"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            android:text="@string/server_select_tip"
            android:textColor="@color/mist"
            android:textSize="12sp"
            android:visibility="gone" />

        <View
            android:id="@+id/statusDot"
            android:layout_width="8dp"
            android:layout_height="8dp"
            android:background="@drawable/bg_status_dot" />
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/fog" />

    <!--状态 1：信息行容器，由代码填充-->
    <LinearLayout
        android:id="@+id/infoContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="20dp"
        android:paddingEnd="20dp" />

    <!--状态 2：节点单选容器，由代码填充-->
    <LinearLayout
        android:id="@+id/nodeContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="20dp"
        android:paddingTop="4dp"
        android:paddingEnd="20dp"
        android:paddingBottom="4dp"
        android:visibility="gone" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:layout_marginTop="4dp"
        android:background="@color/fog" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp">

        <TextView
            android:id="@+id/secondaryButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:background="@drawable/bg_dialog_button_secondary"
            android:gravity="center"
            android:text="@string/server_info_switch"
            android:textColor="@color/ink"
            android:textSize="15sp" />

        <TextView
            android:id="@+id/primaryButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:background="@drawable/bg_dialog_button_primary"
            android:gravity="center"
            android:text="@string/server_info_close"
            android:textColor="@color/text_color_white"
            android:textSize="15sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 7: 确认资源可编译**

```bash
./gradlew :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL。若报 `tools` 命名空间未声明，按 Step 4 的说明处理。

---

### Task 8: 弹窗状态 1 与顶栏入口

**Files:**
- Create: `chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/app/main/MainActivity.java`

**Interfaces:**
- Consumes: `ServerNodeManager.currentHostSpec` / `pinnedHost` / `nodeOf`、`HostLatencyProbe.probeAll` / 哨兵常量、`ChatManager.getConnectedHost()`、Task 7 的资源 id、`Config.IM_SERVER_NODES`、`Config.IM_SERVER_PORT`
- Produces:
  - `ServerInfoDialog(Activity activity, LifecycleOwner owner, LiveData<Integer> connectionStatus)` 构造器
  - `ServerInfoDialog.show()`（继承自 `Dialog`）

- [ ] **Step 1: 写 ServerInfoDialog 的状态 1**

创建 `chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java`：

```java
package cn.wildfire.chat.kit.net;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.wildfire.chat.app.Config;
import cn.wildfire.chat.kit.ChatManagerHolder;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.client.ConnectionStatus;

/**
 * 「当前连接服务」弹窗。两个状态共用一个 Dialog 实例：
 * 状态 1 展示当前连接信息，状态 2 选择节点。切换只改标题、容器可见性与按钮文案。
 */
public class ServerInfoDialog extends Dialog {

    private final LifecycleOwner lifecycleOwner;
    private final LiveData<Integer> connectionStatus;

    private TextView dialogTitle;
    private TextView nodeListTip;
    private View statusDot;
    private LinearLayout infoContainer;
    private LinearLayout nodeContainer;
    private TextView secondaryButton;
    private TextView primaryButton;

    private TextView hostValue;
    private TextView statusValue;
    private TextView latencyValue;

    private HostLatencyProbe.Cancelable runningProbe;
    private Observer<Integer> statusObserver;

    public ServerInfoDialog(@NonNull Activity activity,
                            @NonNull LifecycleOwner lifecycleOwner,
                            @NonNull LiveData<Integer> connectionStatus) {
        super(activity, R.style.ServerInfoDialog);
        this.lifecycleOwner = lifecycleOwner;
        this.connectionStatus = connectionStatus;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.server_info_dialog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout((int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dialogTitle = findViewById(R.id.dialogTitle);
        nodeListTip = findViewById(R.id.nodeListTip);
        statusDot = findViewById(R.id.statusDot);
        infoContainer = findViewById(R.id.infoContainer);
        nodeContainer = findViewById(R.id.nodeContainer);
        secondaryButton = findViewById(R.id.secondaryButton);
        primaryButton = findViewById(R.id.primaryButton);

        buildInfoRows();
        showInfoState();

        statusObserver = new Observer<Integer>() {
            @Override
            public void onChanged(Integer status) {
                renderConnectionStatus(status);
            }
        };
        connectionStatus.observe(lifecycleOwner, statusObserver);
    }

    /** 状态 1 的 5 行信息。行顺序即截图顺序。 */
    private void buildInfoRows() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        addRow(inflater, R.string.server_info_name_label,
                getContext().getString(R.string.server_info_name_value), false);
        hostValue = addRow(inflater, R.string.server_info_host_label, "", true);
        addRow(inflater, R.string.server_info_port_label,
                String.valueOf(Config.IM_SERVER_PORT), true);
        statusValue = addRow(inflater, R.string.server_info_status_label, "", true);
        latencyValue = addRow(inflater, R.string.server_info_latency_label,
                getContext().getString(R.string.server_latency_unknown), true);
    }

    private TextView addRow(LayoutInflater inflater, int labelRes, String value, boolean withDivider) {
        if (withDivider) {
            View divider = new View(getContext());
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(getContext().getResources().getColor(R.color.fog));
            infoContainer.addView(divider);
        }
        View row = inflater.inflate(R.layout.server_info_row, infoContainer, false);
        ((TextView) row.findViewById(R.id.rowLabel)).setText(labelRes);
        TextView valueView = row.findViewById(R.id.rowValue);
        valueView.setText(value);
        infoContainer.addView(row);
        return valueView;
    }

    private void showInfoState() {
        dialogTitle.setText(R.string.server_info_title);
        nodeListTip.setVisibility(View.GONE);
        infoContainer.setVisibility(View.VISIBLE);
        nodeContainer.setVisibility(View.GONE);
        secondaryButton.setText(R.string.server_info_switch);
        primaryButton.setText(R.string.server_info_close);
        primaryButton.setOnClickListener(v -> dismiss());
        secondaryButton.setOnClickListener(v -> showSelectState());

        renderHost();
        renderConnectionStatus(connectionStatus.getValue());
        probeCurrentHost();
    }

    /** Task 9 实现，此处先留空跳转以便状态 1 可独立验证。 */
    private void showSelectState() {
    }

    private void renderHost() {
        hostValue.setText(displayHost());
    }

    /**
     * 优先展示真正连通的 host；取不到时退回用户固定的 host，
     * 都没有则说明处于自动轮询且尚未连上。
     */
    private String displayHost() {
        String connected = ChatManagerHolder.gChatManager == null
                ? null : ChatManagerHolder.gChatManager.getConnectedHost();
        if (connected != null && !connected.trim().isEmpty()) {
            return connected;
        }
        String pinned = ServerNodeManager.pinnedHost(getContext());
        if (pinned != null) {
            return pinned;
        }
        return getContext().getString(R.string.server_node_auto);
    }

    private void renderConnectionStatus(Integer status) {
        int textRes;
        int colorRes;
        if (status != null && status == ConnectionStatus.ConnectionStatusConnected) {
            textRes = R.string.server_status_connected;
            colorRes = R.color.leaf;
        } else if (status != null && status == ConnectionStatus.ConnectionStatusConnecting) {
            textRes = R.string.server_status_connecting;
            colorRes = R.color.cobalt;
        } else {
            textRes = R.string.server_status_unconnected;
            colorRes = R.color.coral;
        }
        int color = getContext().getResources().getColor(colorRes);
        statusValue.setText(textRes);
        statusValue.setTextColor(color);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));

        if (infoContainer.getVisibility() == View.VISIBLE) {
            renderHost();
        }
    }

    private void probeCurrentHost() {
        cancelProbe();
        String host = probeTargetHost();
        if (host == null) {
            latencyValue.setText(R.string.server_latency_unknown);
            return;
        }
        latencyValue.setText(R.string.server_latency_unknown);
        runningProbe = HostLatencyProbe.probeAll(
                Collections.singletonList(host),
                Config.IM_SERVER_PORT,
                (probedHost, latencyMs) -> latencyValue.setText(latencyText(latencyMs)));
    }

    /** 探测目标：实连 host 优先，其次固定 host，最后节点表首项。 */
    private String probeTargetHost() {
        String connected = ChatManagerHolder.gChatManager == null
                ? null : ChatManagerHolder.gChatManager.getConnectedHost();
        if (connected != null && !connected.trim().isEmpty()) {
            return connected;
        }
        String pinned = ServerNodeManager.pinnedHost(getContext());
        if (pinned != null) {
            return pinned;
        }
        return Config.IM_SERVER_NODES.isEmpty() ? null : Config.IM_SERVER_NODES.get(0).host;
    }

    private String latencyText(long latencyMs) {
        if (latencyMs >= 0) {
            return getContext().getString(R.string.server_latency_ms, latencyMs);
        }
        if (latencyMs == HostLatencyProbe.RESULT_TIMEOUT) {
            return getContext().getString(R.string.server_latency_timeout);
        }
        if (latencyMs == HostLatencyProbe.RESULT_UNRESOLVED) {
            return getContext().getString(R.string.server_latency_unresolved);
        }
        return getContext().getString(R.string.server_latency_unreachable);
    }

    private void cancelProbe() {
        if (runningProbe != null) {
            runningProbe.cancel();
            runningProbe = null;
        }
    }

    @Override
    public void dismiss() {
        cancelProbe();
        if (statusObserver != null) {
            connectionStatus.removeObserver(statusObserver);
            statusObserver = null;
        }
        super.dismiss();
    }
}
```

`server_latency_ms` 是 `%1$d` 而 `latencyMs` 是 `long`——`getString` 的可变参数会自动装箱为 `Long`，`%d` 接受 `Long`，无需转换。

- [ ] **Step 2: 在 MainActivity 挂上入口**

修改 `chat/src/main/java/cn/wildfire/chat/app/main/MainActivity.java`：

其一，把 `afterViews()` 里的局部变量提为字段。在字段区（`private MeFragment meFragment;` 之后）加：

```java
    private ConversationListViewModel conversationListViewModel;
```

并把 `afterViews()` 中的

```java
        ConversationListViewModel conversationListViewModel = ViewModelProviders
```

改为

```java
        conversationListViewModel = ViewModelProviders
```

其二，在 `initView()` 的 `setTitle(UIUtils.getString(R.string.app_name));` 之后插入：

```java
        bindTitleClick();
```

其三，在 `initView()` 方法之后插入两个新方法：

```java
    /**
     * Toolbar 的标题 TextView 默认不消费触摸，直接给 Toolbar 设监听会让右侧空白区也可点，
     * 所以只给标题那个 TextView 绑事件。WfcBaseActivity.toolbar 字段是 package-private
     * 且不在本包，只能 findViewById。post 是为了确保 setTitle 后 TextView 已创建。
     */
    private void bindTitleClick() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) {
            return;
        }
        toolbar.post(() -> {
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                if (child instanceof TextView) {
                    child.setOnClickListener(v -> showServerInfoDialog());
                    return;
                }
            }
        });
    }

    private void showServerInfoDialog() {
        if (conversationListViewModel == null) {
            return;
        }
        new ServerInfoDialog(this, this, conversationListViewModel.connectionStatusLiveData()).show();
    }
```

补 import（跳过已存在的）：

```java
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import cn.wildfire.chat.kit.net.ServerInfoDialog;
```

- [ ] **Step 3: 构建安装**

```bash
./gradlew :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 人工核对状态 1**

装机启动并登录，点顶栏「飞享IM」：

Expected:
- 弹出圆角卡片，标题「当前连接服务」，右上角色块为绿色（`leaf`）
- 5 行依次为：服务名称=飞享IM 消息服务、连接地址=三个节点之一的真实 host、端口=6789、连接状态=已连接（绿字）、延迟=`NNms`
- 点「关闭」弹窗消失；点「切换连接地址」暂无反应（Task 9 实现）
- 开飞行模式再点标题：连接状态显示「未连接」（红字）、色块红色、延迟显示「超时」或「无法解析」，且不 ANR
- 切系统深色模式后重开弹窗：卡片底色转深、文字转浅，无硬编码色残留

---

### Task 9: 弹窗状态 2 与切换生效

**Files:**
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java`

**Interfaces:**
- Consumes: Task 8 的 `ServerInfoDialog` 私有成员、`ServerNodeManager.pin` / `unpin` / `pinnedHost`、`ChatManager.switchServerHost`、`ServerHostSpec.joinAll`、`HostLatencyProbe.probeAll`
- Produces: 无对外新接口

- [ ] **Step 1: 替换 showSelectState 占位实现**

在 `ServerInfoDialog` 中，把 Task 8 留下的空方法

```java
    /** Task 9 实现，此处先留空跳转以便状态 1 可独立验证。 */
    private void showSelectState() {
    }
```

替换为：

```java
    private void showSelectState() {
        cancelProbe();
        dialogTitle.setText(R.string.server_select_title);
        nodeListTip.setVisibility(View.VISIBLE);
        infoContainer.setVisibility(View.GONE);
        nodeContainer.setVisibility(View.VISIBLE);
        secondaryButton.setText(R.string.server_select_back);
        primaryButton.setText(R.string.server_select_confirm);
        secondaryButton.setOnClickListener(v -> showInfoState());
        primaryButton.setOnClickListener(v -> confirmSelection());

        selectedHost = ServerNodeManager.pinnedHost(getContext());
        buildNodeRows();
        probeAllNodes();
    }

    /** 构建「自动」+ 各节点的单选行。每次进入状态 2 重建，避免复用脏状态。 */
    private void buildNodeRows() {
        nodeContainer.removeAllViews();
        nodeRows.clear();
        LayoutInflater inflater = LayoutInflater.from(getContext());

        // HOST_AUTO 不是真实 host，仅作「自动轮询」这一行的标识
        addNodeRow(inflater, HOST_AUTO,
                getContext().getString(R.string.server_node_auto),
                getContext().getString(R.string.server_node_auto_host));
        for (ServerNode node : Config.IM_SERVER_NODES) {
            addNodeRow(inflater, node.host, node.name, node.host);
        }
        refreshRadios();
    }

    private void addNodeRow(LayoutInflater inflater, String host, String name, String subtitle) {
        View row = inflater.inflate(R.layout.server_node_item, nodeContainer, false);
        ((TextView) row.findViewById(R.id.nodeName)).setText(name);
        ((TextView) row.findViewById(R.id.nodeHost)).setText(subtitle);
        row.setOnClickListener(v -> {
            selectedHost = HOST_AUTO.equals(host) ? null : host;
            refreshRadios();
        });
        nodeContainer.addView(row);
        nodeRows.put(host, row);
    }

    private void refreshRadios() {
        for (Map.Entry<String, View> entry : nodeRows.entrySet()) {
            boolean checked = HOST_AUTO.equals(entry.getKey())
                    ? selectedHost == null
                    : entry.getKey().equals(selectedHost);
            ((RadioButton) entry.getValue().findViewById(R.id.nodeRadio)).setChecked(checked);
        }
    }

    private void probeAllNodes() {
        List<String> hosts = new ArrayList<>();
        for (ServerNode node : Config.IM_SERVER_NODES) {
            hosts.add(node.host);
            View row = nodeRows.get(node.host);
            if (row != null) {
                row.findViewById(R.id.nodeProgress).setVisibility(View.VISIBLE);
                ((TextView) row.findViewById(R.id.nodeLatency)).setText("");
            }
        }
        runningProbe = HostLatencyProbe.probeAll(hosts, Config.IM_SERVER_PORT,
                (host, latencyMs) -> {
                    View row = nodeRows.get(host);
                    if (row == null) {
                        return;
                    }
                    row.findViewById(R.id.nodeProgress).setVisibility(View.GONE);
                    ((TextView) row.findViewById(R.id.nodeLatency)).setText(latencyText(latencyMs));
                });
    }

    /**
     * 选中项与当前固定状态一致时不重连，避免无谓地断开一条好连接。
     * 固定单个节点传该 host，自动则传全部 host 恢复轮询。
     */
    private void confirmSelection() {
        String currentPinned = ServerNodeManager.pinnedHost(getContext());
        boolean unchanged = (selectedHost == null && currentPinned == null)
                || (selectedHost != null && selectedHost.equals(currentPinned));
        if (unchanged) {
            showInfoState();
            return;
        }

        String hostSpec;
        if (selectedHost == null) {
            ServerNodeManager.unpin(getContext());
            hostSpec = ServerHostSpec.joinAll(Config.IM_SERVER_NODES);
        } else {
            ServerNodeManager.pin(getContext(), selectedHost);
            hostSpec = selectedHost;
        }
        if (ChatManagerHolder.gChatManager != null) {
            ChatManagerHolder.gChatManager.switchServerHost(hostSpec);
        }
        showInfoState();
    }
```

- [ ] **Step 2: 补字段与 import**

在 `ServerInfoDialog` 的字段区（`private Observer<Integer> statusObserver;` 之后）加：

```java
    /** 「自动轮询」行的标识，不是真实 host。 */
    private static final String HOST_AUTO = "__auto__";

    private final Map<String, View> nodeRows = new LinkedHashMap<>();
    private String selectedHost;
```

补 import（跳过已存在的）：

```java
import android.widget.RadioButton;

import java.util.LinkedHashMap;
import java.util.Map;
```

`java.util.ArrayList`、`java.util.List`、`android.view.LayoutInflater` 在 Task 8 已引入。

- [ ] **Step 3: 让 showInfoState 复位按钮监听**

`showInfoState()` 在 Task 8 已经每次都重设两个按钮的 `setOnClickListener`，从状态 2 返回时会正确覆盖。核对 `showInfoState()` 中这两行存在且顺序在 `setText` 之后：

```java
        primaryButton.setOnClickListener(v -> dismiss());
        secondaryButton.setOnClickListener(v -> showSelectState());
```

- [ ] **Step 4: 构建安装**

```bash
./gradlew :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 人工验收全流程**

登录后逐项核对：

1. 点标题 →「切换连接地址」：标题变「选择连接服务」，右上出现小字「建连耗时」，4 行单选（自动 + 3 节点），「自动」默认选中，3 个节点行先转圈后显示 `NNms`
2. 选「主节点」→「确定」：回到状态 1，连接状态先「正在连接…」后「已连接」，连接地址变为 `backend-tcp.fsharechat.cn`；收发一条消息确认正常，且**未被要求重新登录**
3. 重开弹窗 →「切换连接地址」：「主节点」为选中态
4. 杀进程重启 App，点标题：连接地址仍为 `backend-tcp.fsharechat.cn`（持久化生效）
5. 选「自动（集群轮询）」→「确定」：重连成功；重启 App 后连接地址可能变为任一节点（轮询恢复）
6. 同一节点重复点「确定」：连接状态**不出现**「正在连接…」（未触发重连）
7. 连续快速切换 5 次以上：不崩溃，日志中无连回旧 host 的记录
8. 切换后核对连接不重复：

```bash
adb shell "netstat -tn | grep 6789"
```

Expected: 仅一条 ESTABLISHED 指向当前所选节点

9. 深色模式下重走 1–2 步，核对节点列表配色

- [ ] **Step 6: 确认全量单测仍通过**

```bash
./gradlew :chat:testDebugUnitTest :push-sdk:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 确认工作区改动完整且未提交**

```bash
git status --short
```

Expected: 列出本计划涉及的所有新增／修改文件，且 `git log --oneline -1` 仍指向实施前的那个 commit。**不要执行 git commit。**

---

## 自审结果

对照 spec 逐节核查：

- 入口（顶栏标题可点）→ Task 8
- 状态 1 五行信息 + 状态色块 → Task 7（资源）+ Task 8（渲染）
- 状态 2 单选列表 + 建连耗时 + 返回/确定 → Task 7 + Task 9
- 关闭按钮 `cobalt` → Task 7 `bg_dialog_button_primary`
- 深色模式走 token → Global Constraints + Task 7／8 验收步骤
- 延迟 = host+port TCP 建连耗时 → Task 4
- 连接地址取实连 host（含 AIDL 只读通道）→ Task 1 + Task 5 + Task 8
- 节点固定 + 持久化 + 不自动回退 → Task 2 + Task 3 + Task 9
- 节点表单点维护 → Task 2
- switchServer 顺序与三个竞态 → Task 6
- `reconnect()` null 判断修复 → Task 6 Step 1
- 不发 DISCONNECT、不重新登录 → Task 6（说明）+ Task 9 Step 5.2（验收）
- 错误处理（未连接显示 ——、选中项相同不重连、探测超时、dismiss 后丢弃回调）→ Task 4／8／9
- 测试（ServerHostSpec、RoundRobinHostSelector、HostLatencyProbe、手动验收）→ Task 1／2／4 + 各任务验收步骤

两处对 spec 的有意偏离，均已在对应任务中注明理由：

1. `ServerNode` 独立成文件而非 `Config` 嵌套类——`Config` 静态初始化依赖 `android.os.Environment`，否则纯 JUnit 测不了（Task 2）。
2. `AndroidNIOClient.getCurrentHost()` 委托给 `RoundRobinHostSelector` 而非另存字段——避免两处状态不一致（Task 1 Step 5）。

spec 中「`ServerNodeManager` 单测」一项无法在不引入 Robolectric 的前提下完成（依赖 `SharedPreferences`），已改为：纯逻辑下沉到 `ServerHostSpec` 并单测（Task 2），`ServerNodeManager` 本身由 Task 3 Step 4 与 Task 9 Step 5.4 的真机验收覆盖。
