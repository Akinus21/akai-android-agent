# akai-android-agent

Android worker app for the [akai-net](https://github.com/Akinus21/akai-net) distributed inference system.

## Architecture

- **Rust core** (`rust/`): mTLS tunnel client, Ed25519 auth, queue client — compiled to `.so` via `cargo-ndk`
- **Kotlin UI** (`app/`): Jetpack Compose interface, foreground service for background tunnel
- **rpc-server**: Bundled as a native arm64 binary in assets, executed as a subprocess

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
cp target/armv7-linux-androideabi/release/libakai_tunnel_android.so ../app/src/main/jniLibs/armeabi-v7a/

# Build APK
cd ..
./gradlew assembleDebug
```

## Usage

1. Enter your queue URL and username
2. Tap **Initialize & Connect** — authenticates with Duo 2FA, fetches tunnel certs
3. Tap **Start Worker** — connects mTLS tunnel and starts rpc-server
4. The worker runs as a foreground service (survives app minimize)

## How it works

```
Android Phone                    Hetzner VPS
┌──────────────┐   mTLS tunnel   ┌──────────────┐
│ akai-agent   │◄───────────────►│ tunnel server │
│  ┌────────┐  │   port 443      │  (Caddy SNI)  │
│  │ rpc-   │  │                 └──────┬───────┘
│  │ server │  │                       │
│  └───┬────┘  │                 ┌──────┴───────┐
│      │GPU    │                 │ llama-server  │
│      ▼       │                 │  (akai-net)   │
└──────────────┘                 └──────────────┘
```