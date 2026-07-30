# 连接服务信息弹窗与节点切换 · 设计

日期：2026-07-30

## 目标

点击主界面顶栏「飞享IM」标题，弹出「当前连接服务」信息弹窗，展示服务名称、连接地址、端口、连接状态与延迟；提供「切换连接地址」入口，让用户在预置集群节点之间选择并固定一个节点，或恢复自动轮询。风格沿用现有 token 化配色，白天/夜间模式均适配。

## 现状

- 顶栏标题在 `MainActivity.java:124-140` 随连接状态更新为 `飞享IM` / `飞享IM(正在连接...)` / `飞享IM(未连接)`，当前不可点击。
- `Config.IM_SERVER_HOST` 是 `":"` 分隔的 3 个 host，`RoundRobinHostSelector`(push-sdk) 在**每次连接时轮询**取下一个。用户无法指定节点，选择也不持久化。
- `ClientService` 运行在独立进程 `:marsservice`（`client/src/main/AndroidManifest.xml:17`），跨进程通信走 `IRemoteClient` AIDL。
- 已有 `setServerAddress(host, port)` AIDL，但它只写 `mHost/mPort`，真正生效点是登录路径 `connect(userId, token)` → `initProto()` → `JavaProtoLogic.connect(mHost, mPort)`。
- 没有任何延迟采集。
- `AboutActivity.java:31` 已在明文展示 host + port，故本功能不额外收敛服务器信息可见性，对所有用户开放。

### 两个必须绕开的坑

1. `AbstractProtoService.disConnect()` (`:551`) 会 `imMemoryStore.stop()` 并把 `userDisconnect = true` 永久堵死后续 `reconnect()`。它是「退出登录」路径（`SettingActivity.java:22`）。**切换节点不能走它**，否则会话与本地消息库被拆除且需重新登录。
2. `AbstractProtoService.connect()` (`:108`) 新建 `AndroidNIOClient` 但不关闭旧实例。目前只在登录时调用一次所以不暴露问题；运行时切换必须显式清理，否则泄漏 socket 与 selector 线程。

## 交互设计

### 入口

`MainActivity.initView()` 中遍历 Toolbar 子 View 取到标题 TextView 并绑定点击（Toolbar 标题默认不消费触摸，直接给 Toolbar 设监听会让右侧空白区也可点）。不改 `ToolbarTitle.Main` 样式，不加下拉指示符——现有的 `(未连接)` 文字后缀已在承担状态提示。

### 弹窗

一个居中的 `Dialog`（`surface` 圆角卡片），内部两个状态互相切换、不销毁重建。

**状态 1 · 当前连接服务**

| 元素 | 规格 |
| --- | --- |
| 标题栏 | 文案「当前连接服务」+ 右侧 8dp 状态色块：`leaf` 已连接 / `cobalt` 连接中 / `coral` 未连接 |
| 信息行 | 服务名称 / 连接地址 / 端口 / 连接状态 / 延迟，共 5 行；label 用 `mist`，value 用 `ink`，行间 `fog` 发丝线 |
| 服务名称值 | 固定文案「飞享IM 消息服务」（照截图）。节点身份由「连接地址」行体现，不在此行重复 |
| 连接地址值 | **实际建立连接的 host**（见「连接地址的取值」）。取不到时：pin 状态显示所选节点 host，自动状态显示「自动（集群轮询）」 |
| 端口值 | `Config.IM_SERVER_PORT` |
| 连接状态值 | `leaf`「已连接」、`mist`「正在连接…」、`coral`「未连接」 |
| 延迟值 | 如 `32ms`；探测中显示转圈；未连接或探测失败显示 `——` |
| 底部按钮 | 左「切换连接地址」（`surface` 底 / `ink` 字），右「关闭」（`cobalt` 底 / 白字） |

**状态 2 · 选择连接服务**

- 标题改为「选择连接服务」，右上角小字标注「建连耗时」。
- 单选列表：`自动（集群轮询）` / `上海节点` / `主节点` / `备节点 S2`。每行为 radio + 节点名 + 灰色小字 host，行尾显示建连耗时 `32ms` / `超时` / `无法解析` / 探测中转圈。「自动」行不显示耗时。
- 底部按钮：左「返回」，右「确定」（`cobalt` 底 / 白字）。
- 点「确定」后写入持久化、触发重连，并**回到状态 1**，用户直接看到新地址与连接状态变化，弹窗不关闭。

### 深色模式

所有颜色取 `ink / paper / surface / mist / fog / leaf / coral / cobalt` token。`values-night/colors.xml` 与 `color-night/` 已有对应值，无需额外适配代码。

## 连接地址的取值

自动（轮询）模式下，究竟连到了哪个 host 只有 `:marsservice` 进程知道——`RoundRobinHostSelector` 的轮询结果不出进程。而「连接地址」是本弹窗的核心排障信息，不能显示为「未知」。因此补一条只读通道：

- `RoundRobinHostSelector` 新增 `getCurrentHost()`，返回最近一次 `getNextHost()` 的结果（`index` 前一位；初始为 null）。
- `AndroidNIOClient` 在 `connect()` 里把选出的 host 存字段，暴露 `getCurrentHost()`。
- `AbstractProtoService` 暴露 `getConnectedHost()` 转发之。
- `IRemoteClient.aidl` 新增**同步**方法 `String getConnectedHost();`（不能 `oneway`，需要返回值），`ClientService` 转发 `JavaProtoLogic.getConnectedHost()`。
- `ChatManager` 新增 `getConnectedHost()` 包装，返回 null 时 UI 按上表兜底。

这也让「延迟」探测有了准确目标：**优先探测 `getConnectedHost()` 返回的 host**，取不到时退回 pin 的 host 或节点表首项。

## 延迟指标

统一为**到 `host` + `Config.IM_SERVER_PORT`(6789) 的 TCP 建连耗时**：`Socket.connect(new InetSocketAddress(host, port), 2000)` 计时后立即 close。

选择这个指标的理由：

- 状态 1 的「延迟」与状态 2 列表中的数字是同一指标，可直接横向比较。
- 探测路径与端口和真实 IM 连接完全一致，不会出现「ICMP 通但 IM 端口不通」的假绿灯。
- 探测逻辑全部留在 `chat` 模块，无需跨 `:marsservice` 进程取心跳 RTT，也无需为延迟本身新增 AIDL。

已知取舍：若服务端 TCP 能 accept 但 IM 业务逻辑卡死，建连耗时仍显示正常。该场景由「连接状态」行覆盖——它由心跳驱动的 `connectionStatusLiveData` 提供，会掉到「未连接」。两行各管一件事：连接状态管应用层健康，延迟管网络往返。

## 节点固定语义

- 列表首项「自动（集群轮询）」= 传全量 `":"` 拼接串给 `ChatManager`，`RoundRobinHostSelector` 恢复轮询。
- 选中具体节点 = 只传该单个 host。`RoundRobinHostSelector` 拿到单 host 时永远返回它，**pin 语义无需改动 `push-sdk` 的选择逻辑**（`push-sdk` 只加一个只读 getter，见「连接地址的取值」）。
- 选择写入 SharedPreferences，异常重连与下次启动都沿用，直到用户改回「自动」。
- 切到不可用节点时**不自动回退**：内建 3 次退避重连（`AbstractProtoService.java:161`）耗尽后停在「未连接」，用户可再开弹窗改回「自动」。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `chat/…/app/Config.java` | 新增静态嵌套类 `Config.ServerNode{String name; String host;}` 与有序 `List<ServerNode> IM_SERVER_NODES`（上海节点 / 主节点 / 备节点 S2，顺序同现状）；`IM_SERVER_HOST` 改为静态初始化时由节点表 `":"` 拼接派生，保证 `AboutActivity.java:31` 继续可用 |
| `chat/…/kit/net/ServerNodeManager.java` **新** | 读写 SharedPreferences（`Config.SP_NAME` = `config`，key `pinned_im_host`）；`currentHostSpec()` → pin 了返回单 host，否则返回全量串；`currentNode()` / `pin(host)` / `unpin()` |
| `chat/…/kit/net/HostLatencyProbe.java` **新** | 线程池并发探测 `(host, port)`，2s 超时，结果回调主线程；持 cancel 标志，弹窗 dismiss 后丢弃回调 |
| `chat/…/kit/net/ServerInfoDialog.java` **新** | 两状态弹窗；观察传入的 `connectionStatusLiveData`，dismiss 时 removeObserver |
| `chat/…/kit/WfcUIKit.java:105` | `ChatManager.init(app, ServerNodeManager.currentHostSpec(app), Config.IM_SERVER_PORT)` |
| `chat/…/app/main/MainActivity.java` | Toolbar 标题 TextView 绑定点击弹出弹窗；持有 `ConversationListViewModel` 引用以便把 LiveData 传入弹窗 |
| `push-sdk/…/util/RoundRobinHostSelector.java` | 新增 `getCurrentHost()`（只读，返回最近一次选出的 host） |
| `push-sdk/…/client/AndroidNIOClient.java` | `connect()` 里记录本次选出的 host 到字段；新增 `getCurrentHost()` |
| `client/…/aidl/…/IRemoteClient.aidl` | 新增 `oneway void switchServerAddress(in String host, in int port);` 与同步方法 `String getConnectedHost();` |
| `client/…/client/ClientService.java` | 实现两个方法：`switchServerAddress` 更新 `mHost/mPort` → `JavaProtoLogic.switchServer(host, port)`；`getConnectedHost` 转发 `JavaProtoLogic.getConnectedHost()` |
| `client/…/proto/JavaProtoLogic.java` | 新增静态转发 `switchServer(host, port)` 与 `getConnectedHost()` |
| `client/…/proto/AbstractProtoService.java` | 新增 `switchServer(host, port)`，顺序见「切换时不产生重复连接」一节；新增 `getConnectedHost()` 转发 `androidNIOClient.getCurrentHost()`（client 为空返回 null）；修 `reconnect()`(`:189`) 把 `androidNIOClient` 的 null 判断挪进 runnable 内部 |
| `client/…/remote/ChatManager.java` | 新增 `switchServerHost(String hostSpec)`：更新 `SERVER_HOST` 字段（保证 service 重新 bind 时 `:3255` 用的是新值）+ 调 AIDL。新增 `getConnectedHost()` 包装（未 bind / `RemoteException` 时返回 null） |
| 资源 | `server_info_dialog.xml`、信息行 item、单选行 item、圆角背景 drawable；文案入 `strings.xml` |

`switchServer` 不发 DISCONNECT 信号、不动 `imMemoryStore`、不碰 `logined` 标志，重连后 `onConnected()` 照常 `sendConnectMessage()` 用已有 token 重新鉴权。同集群 token 通用，用户无需重新登录，本地消息库不受影响。

## 切换时不产生重复连接

每个 `AndroidNIOClient` 在构造函数里自建一个 `AsyncServer("AndroidNIOClient")`（独立 selector 线程），所以一个泄漏的 client = 一条泄漏线程 + 一条泄漏 socket。必须显式关闭旧实例。

`AndroidNIOClient.close()` 本身是彻底的：`cancelSilently()` 取消连接 future → 摘掉 socket 的 data/closed callback → 关 socket → `asyncServer.stop()` 并置 null。`AsyncServer.stop()` 中 `shutdownKeys()` 强杀现存连接，且在**非 selector 线程**调用时会 `semaphore.acquire()` 阻塞到关闭完成。因此 `close()` 返回后旧连接确已消失。

### 必须处理的三个竞态

1. **积压的 reconnect runnable 会打到新 client 上。** `AbstractProtoService.reconnect()`(`:189`) 在方法外判 `androidNIOClient != null`，但 runnable 内部又重新读该字段；队列中积压的 runnable 执行时字段已被替换或置空 → 轻则在新 client 上多调一次 connect（被 `connectStatus` 挡住，无害），重则 NPE。
   - `AndroidNIOClient.post()` 用的 `synchronousResolverWorkers` 是 `ThreadPoolExecutor(1, 4, …, LinkedBlockingQueue)`；**无界队列使其永不扩容到 4 线程，实际单线程**，且为 `static` 跨实例共享。故 `switchServer` 也经 `post()` 提交即可与积压 runnable 串行化。
   - 同时把 `reconnect()` 的 null 判断挪进 runnable 内部。
2. **`close()` 前未摘 `pushMessageCallback` 会自动排一次重连。** `receiveException()`(`:155`) 在 `reconnectNum <= 3` 时排重连定时器，那次重连会连回**旧 host**。`close()` 内部虽已先摘 socket callback（`onCompleted` 不会走），但 60s 连接超时 runnable 仍会调 `receiveException`。按 `stopProtoService()` 既有写法先 `setPushMessageCallback(null)` 再 `close()`。
3. **`alarmWrapper` 上待触发的 `reconnectTimer`** 必须先 `cancelReconnectTimer()`。

### `switchServer(host, port)` 落定顺序

```
经 AndroidNIOClient.post() 提交（与积压 reconnect 串行，且不在主线程 —— stop() 会阻塞）
  → cancelHeartTimer() / cancelReconnectTimer() / clearSendMessageTimerMap()
  → androidNIOClient.setPushMessageCallback(null)
  → androidNIOClient.close()        // 阻塞至旧 AsyncServer 停妥
  → androidNIOClient = null
  → reconnectNum = 0
  → JavaProtoLogic.onConnectionStatusChanged(ConnectionStatusConnecting)
  → connect(newHost, port)          // 复用现有 connect：新建 client + reconnect()
```

### 服务端侧

不发 DISCONNECT 信号（那是登出语义、会清 session），旧节点只看到 TCP FIN，按正常掉线处理；新连接落在另一个节点上，是全新 session。快速来回切换时旧节点可能在自身超时窗口内短暂保留半开会话，由服务端既有的重连与 `local_messageId` 去重机制（要求 server ≥ 2.2.0）处理。

## 数据流

```
点标题 → ServerInfoDialog
  ├ 状态1：ChatManager.getConnectedHost() ?: ServerNodeManager 兜底
  │        + connectionStatusLiveData + HostLatencyProbe(该 host, 6789)
  └ 状态2：HostLatencyProbe(全部 host, 6789) 并发
       点确定 → ServerNodeManager.pin/unpin（SharedPreferences）
              → ChatManager.switchServerHost(spec)
              → AIDL switchServerAddress → ClientService → JavaProtoLogic
              → AbstractProtoService.switchServer（重建 NIO client）
              → onConnectionStatusChanged 回流 → 状态1 与顶栏标题同步刷新
```

## 错误处理

- 未连接 / 未登录：延迟显示 `——`，连接状态显示「未连接」（`coral`），「切换连接地址」仍可用。
- 选中项与当前项相同：点「确定」直接回状态 1，不触发重连。
- 探测 2s 超时 → 「超时」；`UnknownHostException` → 「无法解析」。
- 弹窗 dismiss 后所有探测回调丢弃，不触碰已销毁 View。
- 切到不可用节点：3 次退避重连耗尽后停在「未连接」，不自动回退。
- `getConnectedHost()` 是同步跨进程 binder 调用，只在弹窗打开与连接状态变化时调用（频率极低），`RemoteException` 或未 bind 时返回 null 走兜底显示。

## 测试

JVM 单元测试：

- `ServerNodeManager`：pin / auto 状态 → `currentHostSpec()` 映射；pin 到不在节点表中的 host 时降级为 auto。
- `RoundRobinHostSelector`：单 host 时恒定返回该 host；`getCurrentHost()` 在首次 `getNextHost()` 前返回 null，之后与最近一次返回值一致（含跨轮回绕）。
- `HostLatencyProbe`：对本地 `ServerSocket` 测得非负耗时；对不可路由地址在 2s 左右返回超时。

手动验收：

- 真机依次切换三个节点，每次顶栏状态回到「已连接」且能正常收发消息。
- **连接不重复**：切换后用 `adb shell dumpsys` / `netstat` 或 logcat 确认只剩一条到新节点的 ESTABLISHED，旧节点连接已消失；确认没有多余的 `AndroidNIOClient` selector 线程残留。
- **快速连切**：连续快速切换 5 次以上，确认不崩（竞态 1）、不出现连回旧 host 的日志（竞态 2）。
- 切换后杀进程重启，确认仍连在所选节点。
- 改回「自动」后确认恢复轮询。
- 飞行模式下打开弹窗，确认显示「未连接」+ `——` 且不 ANR。
- 白天 / 夜间模式下两个状态各截图核对配色。
