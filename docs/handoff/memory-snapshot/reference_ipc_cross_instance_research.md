# IPC Research: Cross-Instance IntelliJ Communication

## Context
Two IntelliJ IDEA instances (JVM processes) on same machine need bidirectional real-time JSON messaging (1-50KB).
Plugin targets JDK 21 (JBR), uses OkHttp + Kotlin coroutines. Must work on Windows corporate (locked-down) + macOS.

## RECOMMENDATION: Unix Domain Sockets (JEP 380, native Java 16+)

### Why This Wins
- Native JDK 21 support via `java.nio.channels.SocketChannel` + `java.net.UnixDomainSocketAddress` -- zero dependencies
- No ports, no firewall, no AV triggers -- uses filesystem path not TCP/IP
- Works on Windows 10+ (Build 17063+, AF_UNIX) and macOS natively
- Sub-millisecond latency (kernel-level, no TCP/IP stack overhead)
- Filesystem permissions for access control
- IntelliJ's own platform uses similar socket-based IPC for instance locking

### Implementation Strategy
- Server instance creates `UnixDomainSocketAddress` at `~/.workflow-orchestrator/ipc.sock` (macOS) or `%LOCALAPPDATA%\WorkflowOrchestrator\ipc.sock` (Windows)
- Client instance connects via same path
- Wrap in Kotlin coroutines with `Dispatchers.IO`
- Frame JSON messages with length prefix (4-byte int + JSON bytes)
- Discovery: write PID + socket path to a known lock file

## Full Evaluation Matrix

### 1. Local TCP/WebSocket (localhost:port)
- **Windows corporate**: PROBLEMATIC. Windows Firewall prompts when JVM binds a port (even localhost). Corporate group policy may auto-deny. AV (CrowdStrike, Sophos) flags new listening ports. Some corporate firewalls DO block localhost.
- **macOS**: Works fine, macOS firewall is less aggressive on localhost.
- **Latency**: ~0.1-1ms (TCP loopback), sub-second easily.
- **Complexity**: Low (OkHttp WebSocket client already in project).
- **Dependencies**: None new (OkHttp already available). Need lightweight WebSocket server (Ktor/Netty/raw ServerSocket).
- **Port management**: Must find free port, write to discovery file. Port conflicts with other apps possible.
- **Reliability**: Good once connected. Connection establishment may fail on corporate Windows.
- **Verdict**: Second-best option. Works but has corporate firewall risk.

### 2. Unix Domain Sockets (JEP 380) -- RECOMMENDED
- **Windows corporate**: EXCELLENT. No network stack involved, no firewall interaction, no AV port scanning alerts. Uses filesystem path. Windows 10 Build 17063+ supports AF_UNIX natively. JDK 21 supports it.
- **macOS**: EXCELLENT. Native AF_UNIX, works perfectly.
- **Latency**: Sub-millisecond. No TCP/IP overhead, kernel-level IPC.
- **Complexity**: Medium. Need to implement framing protocol (length-prefixed messages). ~200 lines of Kotlin.
- **Dependencies**: ZERO. Built into JDK 21 (`java.net.UnixDomainSocketAddress`, `java.nio.channels.SocketChannel`).
- **Port management**: No ports at all. Uses filesystem path. No conflicts possible.
- **Reliability**: Excellent. Stale socket files need cleanup on crash (check PID liveness).
- **Verdict**: Best option. Invisible to firewalls/AV, cross-platform, zero deps.

### 3. Named Pipes (Windows) / UDS (macOS) via libraries
- **Windows corporate**: Good. Named pipes are a core Windows IPC primitive.
- **macOS**: Would need UDS (different API).
- **Complexity**: High. Cross-platform abstraction needed. Libraries: `sbt/ipcsocket`, `pirocks/simple-named-pipes-ipc`, `junixsocket`.
- **Dependencies**: External library needed for Windows Named Pipes (JNI).
- **Verdict**: Unnecessary since JEP 380 unifies UDS across Windows+macOS in JDK 16+.

### 4. Memory-Mapped Files (MappedByteBuffer)
- **Windows corporate**: Fine. No network, no firewall issues.
- **macOS**: Fine.
- **Latency**: Sub-microsecond for reads, but needs polling or signaling mechanism.
- **Complexity**: HIGH. Must implement: ring buffer, synchronization (Unsafe.compareAndSwap), reader/writer coordination, message framing, cleanup. Error-prone.
- **Dependencies**: None (JDK built-in).
- **Reliability**: Fragile. Synchronization bugs are hard to diagnose. No built-in notification mechanism (must poll).
- **Verdict**: Overkill complexity for JSON messaging. Good for ultra-low-latency numeric data, not for 1-50KB JSON messages.

### 5. File-Based with WatchService
- **Windows corporate**: Fine. No network involvement.
- **macOS**: Fine.
- **Latency**: POOR. Windows WatchService uses ReadDirectoryChangesW (native events) but has known issues: event coalescing, buffer overflow, missed events. Typical latency 100ms-2s. macOS kqueue-based, slightly better.
- **Complexity**: Low conceptually, but handling edge cases (partial writes, race conditions, cleanup) is tricky.
- **Dependencies**: None.
- **Reliability**: POOR. WatchService is notoriously unreliable across platforms. Event loss under load.
- **Verdict**: Not suitable for real-time. OK for occasional config sync.

### 6. IntelliJ Platform Built-In IPC
- **What exists**: IntelliJ uses port range 6942-6991 for instance locking (SocketLock). Port 63342 for built-in web server. Plugins can register `RestService` handlers on the built-in server.
- **Cross-instance**: No official plugin API for cross-instance communication. SocketLock is internal and used only for "activate existing instance" on file open.
- **Can we leverage it?**: The built-in server (port 63342) could theoretically be used -- one instance sends HTTP to the other's built-in server. But: need to discover the other instance's port, and this is TCP-based (same firewall concerns as option 1).
- **Verdict**: No suitable built-in cross-instance IPC API for plugins.

### 7. Java RMI
- **Windows corporate**: PROBLEMATIC. Uses dynamic ports. Firewall blocks JRMP traffic. RMI registry on port 1099 may conflict.
- **macOS**: Works but cumbersome.
- **Latency**: ~1-5ms (serialization overhead).
- **Complexity**: Medium, but API is dated and verbose.
- **Dependencies**: Built-in but deprecated trajectory.
- **Reliability**: Fragile with firewalls. Dynamic port allocation is the main issue.
- **Verdict**: Avoid. Legacy technology with firewall problems.

### 8. gRPC with Unix Domain Sockets
- **Windows corporate**: Fine (UDS transport).
- **macOS**: Fine.
- **Latency**: Sub-millisecond.
- **Complexity**: Medium.
- **Dependencies**: HEAVY. grpc-java, grpc-kotlin, protobuf compiler, netty. ~5-10MB of dependencies.
- **Verdict**: Massive overkill for two-process JSON messaging. Use for microservices, not plugin IPC.

## How JetBrains Products Handle IPC
- **JetBrains Gateway/Remote Dev**: SSH + RD protocol (Rider protocol) over TCP. Not applicable for same-machine.
- **Code With Me**: WebSocket over TCP with TLS. Network-oriented, not local IPC.
- **Fleet**: Custom protocol, details not public.
- **IntelliJ instance locking**: TCP socket on localhost port 6942-6991 (SocketLock.java). Simple command protocol.
- **Debugger**: TCP localhost (JDWP protocol).

## Implementation Sketch (Unix Domain Sockets)

```kotlin
// Server side
val path = Path.of(System.getProperty("user.home"), ".workflow-orchestrator", "ipc.sock")
Files.deleteIfExists(path)
val address = UnixDomainSocketAddress.of(path)
val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
server.bind(address)

// Client side
val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
channel.connect(UnixDomainSocketAddress.of(path))

// Message framing: [4-byte length][JSON bytes]
```

## Key Decision Factors
1. Zero firewall/AV interaction (eliminates #1, #6, #7)
2. Zero external dependencies (eliminates #3, #8)
3. Sub-second latency guaranteed (eliminates #5)
4. Reasonable complexity (eliminates #4)
5. Cross-platform with single API (JEP 380 on JDK 21)
