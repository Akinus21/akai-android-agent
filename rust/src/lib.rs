mod tunnel;
mod auth;
mod queue_client;
mod worker;

use jni::objects::JClass;
use jni::objects::JString;
use jni::JNIEnv;
use jni::sys::{jboolean, jint, jstring};

use std::sync::OnceLock;

static DATA_DIR: OnceLock<String> = OnceLock::new();

fn get_data_dir() -> String {
    DATA_DIR.get().cloned().unwrap_or_default()
}

#[macro_export]
macro_rules! alog {
    (INFO, $($arg:tt)*) => {
        {
            let msg = format!($($arg)*);
            let tag = b"akai-agent\0";
            let c_msg = match std::ffi::CString::new(msg) {
                Ok(s) => s,
                Err(_) => std::ffi::CString::new("(log msg contained null byte)").unwrap_or_default(),
            };
            unsafe { $crate::ffi::__android_log_write(4, tag.as_ptr(), c_msg.as_ptr()); }
        }
    };
    (ERROR, $($arg:tt)*) => {
        {
            let msg = format!($($arg)*);
            let tag = b"akai-agent\0";
            let c_msg = match std::ffi::CString::new(msg) {
                Ok(s) => s,
                Err(_) => std::ffi::CString::new("(log msg contained null byte)").unwrap_or_default(),
            };
            unsafe { $crate::ffi::__android_log_write(6, tag.as_ptr(), c_msg.as_ptr()); }
        }
    };
}

pub mod ffi {
    #[link(name = "log")]
    extern "C" {
        pub fn __android_log_write(prio: i32, tag: *const u8, msg: *const u8) -> i32;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeSetDataDir(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) {
    let dir: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let _ = DATA_DIR.set(dir);
    if let Err(e) = std::fs::create_dir_all(get_data_dir()) {
        alog!(ERROR, "failed to create data dir {}: {e}", get_data_dir());
    }
}

// VPN enrollment: call hub's /auth/vpn endpoint, return hub VPN address
#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeEnrollVpn(
    mut env: JNIEnv,
    _class: JClass,
    api_url: JString,
    username: JString,
    worker_name: JString,
) -> jstring {
    let api_url: String = match env.get_string(&api_url) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let username: String = match env.get_string(&username) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let worker_name: String = match env.get_string(&worker_name) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    alog!(INFO, "VPN enrollment: url={}, user={}, worker={}", api_url, username, worker_name);

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return std::ptr::null_mut(),
    };

    let result = rt.block_on(async {
        enroll_vpn(&api_url, &username, &worker_name).await
    });

    match result {
        Ok(result_json) => {
            alog!(INFO, "VPN enrolled: {}", result_json);
            let parsed: serde_json::Value = serde_json::from_str(&result_json).unwrap_or_default();
            let hub_addr = parsed["hub_vpn_addr"].as_str().unwrap_or("");
            // Save config
            let data_dir = get_data_dir();
            let config = serde_json::json!({
                "hub_addr": hub_addr,
                "username": username,
                "worker_name": worker_name,
            });
            let config_path = format!("{}/config.json", data_dir);
            let _ = std::fs::write(&config_path, serde_json::to_string_pretty(&config).unwrap_or_default());

            env.new_string(result_json)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
        Err(e) => {
            alog!(ERROR, "VPN enrollment failed: {e}");
            std::ptr::null_mut()
        }
    }
}

async fn enroll_vpn(api_url: &str, username: &str, worker_name: &str) -> anyhow::Result<String> {
    let url = format!("{}/auth/vpn", api_url.trim_end_matches('/'));
    let client = reqwest::Client::new();
    let resp = client.post(&url)
        .json(&serde_json::json!({
            "username": username,
            "worker_name": worker_name,
        }))
        .timeout(std::time::Duration::from_secs(120))
        .send()
        .await?;

    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        anyhow::bail!("Hub returned {}: {}", status, body);
    }

    let json: serde_json::Value = resp.json().await?;
    let hub_vpn_addr = json["hub_vpn_addr"].as_str()
        .ok_or_else(|| anyhow::anyhow!("missing hub_vpn_addr"))?;

    let wg_config = json["wireguard_config"].as_str().unwrap_or("");

    if !wg_config.is_empty() {
        let data_dir = get_data_dir();
        let wg_dir = format!("{}/wireguard", data_dir);
        let _ = std::fs::create_dir_all(&wg_dir);
        let wg_path = format!("{}/wg0.conf", wg_dir);
        let _ = std::fs::write(&wg_path, wg_config);
        alog!(INFO, "Saved WireGuard config to {}", wg_path);
    }

    let result = serde_json::json!({
        "hub_vpn_addr": hub_vpn_addr,
        "wireguard_config": wg_config,
    });
    Ok(result.to_string())
}

// Start the v2 worker (direct TCP to hub over VPN)
#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeStartWorker(
    mut env: JNIEnv,
    _class: JClass,
    hub_addr: JString,
    worker_id: JString,
    has_gpu: jboolean,
    vram_gb_str: JString,
    rpc_port: jint,
) -> jint {
    let hub_addr: String = match env.get_string(&hub_addr) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let worker_id: String = match env.get_string(&worker_id) {
        Ok(s) => s.into(),
        Err(_) => return -2,
    };
    let vram_gb: f32 = match env.get_string(&vram_gb_str) {
        Ok(s) => {
            let v: String = s.into();
            v.parse().unwrap_or(0.0)
        }
        Err(_) => 0.0,
    };

    let config = worker::WorkerConfig {
        hub_addr,
        worker_id,
        has_gpu: has_gpu != 0,
        vram_gb,
        rpc_port: rpc_port as u16,
    };

    std::thread::spawn(|| {
        let rt = match tokio::runtime::Runtime::new() {
            Ok(rt) => rt,
            Err(e) => {
                alog!(ERROR, "failed to create runtime: {}", e);
                return;
            }
        };

        rt.block_on(async {
            if let Err(e) = worker::run_worker(config).await {
                alog!(ERROR, "worker error: {}", e);
            }
        });
    });

    0
}

// Legacy v1 init (kept for backward compat)
#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    queue_url: JString,
    username: JString,
    device_name: JString,
) -> jint {
    let queue_url: String = match env.get_string(&queue_url) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let username: String = match env.get_string(&username) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let device_name: String = match env.get_string(&device_name) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    let worker_id = format!("{}:{}", username, device_name);

    let data_dir = get_data_dir();
    if data_dir.is_empty() {
        alog!(ERROR, "data dir not set — call setDataDir first");
        return -6;
    }

    let keypair_dir = format!("{}/keys", data_dir);
    if let Err(e) = auth::ensure_keypair_android(&keypair_dir) {
        alog!(ERROR, "keypair init failed: {e}");
        return -2;
    }

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return -3,
    };

    rt.block_on(async {
        let client = queue_client::QueueClient::new(&queue_url, &username);
        let cert_dir = format!("{}/tunnel-certs", data_dir);
        let certs = match client.register_and_fetch_certs(&cert_dir, &worker_id).await {
            Ok(c) => c,
            Err(e) => {
                alog!(ERROR, "init failed: {e}");
                return -4;
            }
        };

        if let Err(e) = save_config_android(&data_dir, &queue_url, &username, &worker_id, &certs.tunnel_host, certs.tunnel_port, "") {
            alog!(ERROR, "failed to save config: {e}");
            return -5;
        }

        0
    })
}

// Legacy v1 connect (kept for backward compat)
#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
    worker_id: JString,
    rpc_port: jint,
) -> jint {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let worker_id: String = match env.get_string(&worker_id) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    let data_dir = get_data_dir();
    let cert_dir = format!("{}/tunnel-certs", data_dir);

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return -3,
    };

    let result = rt.block_on(async {
        let client = tunnel::TunnelClient::from_config(&host, port as u16, &worker_id, rpc_port as u16, &cert_dir);
        client.run().await
    });

    match result {
        Ok(_) => 0,
        Err(e) => {
            alog!(ERROR, "tunnel error: {e}");
            -4
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeGetPublicKey(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let keypair_dir = format!("{}/keys", get_data_dir());
    match auth::get_public_key_pem(&keypair_dir) {
        Ok(pem) => env.new_string(pem)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeSignRequest(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
) -> jstring {
    let msg: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let keypair_dir = format!("{}/keys", get_data_dir());
    match auth::sign_message(&keypair_dir, &msg) {
        Ok(sig) => env.new_string(sig)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeHeartbeat(
    mut env: JNIEnv,
    _class: JClass,
    queue_url: JString,
    username: JString,
    worker_id: JString,
) -> jstring {
    let queue_url: String = match env.get_string(&queue_url) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let username: String = match env.get_string(&username) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let worker_id: String = match env.get_string(&worker_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let data_dir = get_data_dir();
    let key_dir = format!("{}/keys", data_dir);

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return std::ptr::null_mut(),
    };

    let result = rt.block_on(async {
        let client = queue_client::QueueClient::new(&queue_url, &username);
        client.heartbeat(&key_dir, &worker_id, true, 4.0, 50052).await
    });

    match result {
        Ok(resp) => {
            let model = resp.model;
            if let Ok(cfg) = std::fs::read_to_string(format!("{}/android-prefs.json", data_dir)) {
                if let Ok(mut obj) = serde_json::from_str::<serde_json::Value>(&cfg) {
                    obj["model"] = serde_json::json!(model);
                    let _ = std::fs::write(format!("{}/android-prefs.json", data_dir), serde_json::to_string_pretty(&obj).unwrap_or_default());
                    let _ = std::fs::write(format!("{}/config.json", data_dir), serde_json::to_string_pretty(&obj).unwrap_or_default());
                }
            }
            env.new_string(model)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
        Err(e) => {
            alog!(ERROR, "heartbeat failed: {e}");
            std::ptr::null_mut()
        }
    }
}

fn save_config_android(data_dir: &str, queue_url: &str, username: &str, worker_id: &str, tunnel_host: &str, tunnel_port: u16, model: &str) -> anyhow::Result<()> {
    std::fs::create_dir_all(data_dir)?;
    let config = serde_json::json!({
        "queue_url": queue_url,
        "username": username,
        "worker_id": worker_id,
        "tunnel_host": tunnel_host,
        "tunnel_port": tunnel_port,
        "model": model,
    });
    let config_path = format!("{}/config.json", data_dir);
    std::fs::write(&config_path, serde_json::to_string_pretty(&config)?)?;
    let prefs_path = format!("{}/android-prefs.json", data_dir);
    std::fs::write(&prefs_path, serde_json::to_string_pretty(&config)?)?;
    Ok(())
}