# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

飞享 (FshareChat) is an Android IM client built on a custom Java NIO TCP stack. It supports private/group chat, audio/video calls via WebRTC, and integrates push notifications from Xiaomi, Huawei, and Meizu. The app uses a binary custom protocol (FSCMessage protobuf-based) for communication with the backend server.

## Build Commands

```bash
# Build debug APK
./gradlew :chat:assembleDebug

# Build release APK (outputs as Fsharechat-{versionName}-{versionCode}-{date}.apk)
./gradlew :chat:assembleRelease

# Build a specific module
./gradlew :client:assembleDebug

# Clean
./gradlew clean
```

**Requirements:** JDK 11, Android SDK with compileSdkVersion 29.

**Signing:** The keystore `fsharechat.keystore` (password: `fsharechat`, alias: `fshare`) is committed to the repo and used for both debug and release builds.

**CI/CD:** Releases are triggered by pushing a git tag: `git tag v*** && git push --tags`.

## Module Architecture

The project is organized as a multi-module Gradle Android project:

```
push-sdk        ← Java NIO core: AsyncServer, AsyncSocket, ByteBufferList (com.comsince.github.core)
                   Custom binary protocol framing: Header, Signal, SubSignal (com.comsince.github.push)
client          ← Protocol layer: depends on push-sdk
                   AndroidNIOClient — manages the TCP connection lifecycle
                   AbstractProtoService / ProtoService — central IM service; registers all message handlers
                   HeartbeatManager — adaptive heartbeat interval algorithm
                   handler/ — one handler class per message type (SendMessageHandler, ReceiveMessageHandler, etc.)
                   store/ — SQLite-backed message/data store (SqliteDatabaseStore, ImMemoryStore)
push            ← Vendor push notification adapters (Xiaomi, Huawei, Meizu)
                   PushService — bridges vendor push to the app
chat            ← Application module (the APK)
                   app/ — Application, Config, ThemeManager, login, main activity
                   kit/ — Reusable UI components: conversation, contact, group, voip, search, etc.
emojilibrary    ← Emoji picker UI library
imagepicker     ← Image selection UI library
p2penginekit    ← WebRTC audio/video call engine (ChatRoomActivity/Fragment, signaling messages)
```

### Key Architectural Patterns

**TCP connection & heartbeat:** `AndroidNIOClient` (push-sdk) maintains the persistent TCP connection. `AbstractProtoService` owns the connection lifecycle, reconnect logic, and delegates to `HeartbeatManager` for adaptive interval calculation. Heartbeat intervals step up/down in `ONE_STEP_INTERVAL` (5 s) increments between `MIN_HEARTBEAT_INTERVAL` (30 s) and a time-of-day max (60 s day / 120 s night).

**Message protocol:** Messages are framed with a binary `Header` (Signal + SubSignal) then a protobuf `FSCMessage`/`WFCMessage` body. Each server message type is dispatched to a registered `MessageHandler` subclass in `client/handler/`.

**Proto source files:** Located in the sibling repo `../chat-proto/` (i.e. `/fshare/chat-proto/`). Contains `FSCMessage.proto` and `WFCMessage.proto` — they are **identical in content**; two copies exist only for client-side package compatibility (`com.comsince.github.proto` vs `cn.wildfirechat.proto`). Both use **proto2 syntax** (`required`/`optional`). Compile with protobuf 2.5:
```bash
protoc --proto_path=. --java_out=. FSCMessage.proto
```

**Data flow:** `ProtoService` receives raw bytes → dispatches to appropriate `MessageHandler` → handler updates `ImMemoryStore`/`SqliteDatabaseStore` → notifies UI via Android `LiveData` (MVVM with `ViewModel` classes in `kit/viewmodel/`).

**Server configuration:** Edit `chat/src/main/java/cn/wildfire/chat/app/Config.java` to change the IM server host/port, HTTP API host, and TURN server addresses before building.

**Cluster/failover:** `IM_SERVER_HOST` supports multiple hosts separated by `:` for cluster mode switching.

**Push integration:** The `push` module wraps Xiaomi (`MiMessageReceiver`), Huawei (`HuaweiPushRevicer`), and Meizu (`MeizuPushReceiver`) SDK callbacks into a unified `PushMessageHandler` interface. App IDs/keys are set in `chat/build.gradle` → `manifestPlaceholders`.

### Local message ID (v0.9.9+)

Messages now carry a `local_messageId` to detect server ACK failures and avoid duplicates on reconnect. This requires server version ≥ 2.2.0.
