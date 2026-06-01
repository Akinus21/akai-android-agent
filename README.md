# akai-android-agent

Android worker app for the [akai-net](https://github.com/Akinus21/akai-net) distributed inference system.

## Architecture

- **Rust core** (`rust/`): mTLS tunnel client, worker protocol, Ed25519 auth, queue client — compiled to `.so` via `cargo-ndk`
- **Kotlin UI** (`app/`): Jetpack Compose interface, foreground service for background worker
- **Candle** (future): Native Rust ML framework for inference on CPU

## New Architecture (v2)

The new architecture uses direct TCP connection to the akai-net hub:

```
Android Phone                    Akai-Net Hub
┌──────────────┐   TCP/JSON      ┌──────────────┐
│ akai-agent   │◄───────────────►│   :50051      │
│  (Rust)      │                 │  (Rust)      │
│              │                 │              │
│  (Candle)    │                 │              │
│  inference   │                 └──────────────┘
└──────────────┘
```

Workers connect via TCP to port 50051. Protocol is simple JSON messages.

### Worker Protocol

| Message | Direction | Description |
|---------|-----------|-------------|
| `HubMessage::Register` | Worker→Hub | Worker announces capabilities |
| `HubMessage::Heartbeat` | Worker→Hub | Periodic alive check |
| `HubMessage::InferenceRequest` | Hub→Worker | Tokens to process |
| `HubMessage::InferenceResponse` | Worker→Hub | Token + hidden states |

## Building

### Prerequisites

- Android SDK 34
- Rust toolchain with `aarch64-linux-android` and `armv7-linux-androideabi` targets
- `cargo-ndk`

### Steps

```bash
# Build Rust native library
cd rust
cargo ndk -t arm64-v8a -t armeabi-v7a build --release

# Copy .so files
mkdir -p ../app/src/main/jniLibs/arm64-v8a
mkdir -p ../app/src/main/jniLibs/armeabi-v7a
cp target/aarch64-linux-android/release/libakai_tunnel_android.so ../app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-android/release/libakai_tunnel_android.so ../app/src/main/jniLibs/armeabi-v7a/

# Build APK
cd ..
./gradlew assembleDebug
```

## Usage (v2)

1. Enter hub address (IP:50051) and worker ID
2. Tap **Start Worker** — connects to hub and registers
3. The worker runs as a foreground service (survives app minimize)

## JNI Functions

The Rust library exposes these JNI functions:

- `nativeSetDataDir(dataDir: String)` — Set data directory
- `nativeInit(queueUrl, username, deviceName)` — Initialize (v1)
- `nativeHeartbeat(queueUrl, username, workerId)` — Send heartbeat (v1)
- `nativeStartWorker(hubAddr, workerId, layerOffset, numLayers)` — Start v2 worker

## Candle Integration (TODO)

Future work: Replace rpc-server subprocess with Candle (pure Rust ML framework):

- No JNI overhead
- Better for CPU-only inference
- Native Rust integration
- Support for quantized models
