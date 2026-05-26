mod tunnel;
mod auth;
mod queue_client;

use jni::objects::JClass;
use jni::objects::JString;
use jni::JNIEnv;
use jni::sys::{jint, jstring};

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

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    queue_url: JString,
    username: JString,
) -> jint {
    let queue_url: String = match env.get_string(&queue_url) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let username: String = match env.get_string(&username) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

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
        let worker_name = format!("android-{}", username);
        let certs = match client.register_and_fetch_certs(&cert_dir, &worker_name).await {
            Ok(c) => c,
            Err(e) => {
                alog!(ERROR, "init failed: {e}");
                return -4;
            }
        };

        if let Err(e) = save_config_android(&data_dir, &queue_url, &username, &certs.tunnel_host, certs.tunnel_port) {
            alog!(ERROR, "failed to save config: {e}");
            return -5;
        }

        0
    })
}

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

fn save_config_android(data_dir: &str, queue_url: &str, username: &str, tunnel_host: &str, tunnel_port: u16) -> anyhow::Result<()> {
    std::fs::create_dir_all(data_dir)?;

    let config = serde_json::json!({
        "queue_url": queue_url,
        "username": username,
        "tunnel_host": tunnel_host,
        "tunnel_port": tunnel_port,
    });

    let config_path = format!("{}/config.json", data_dir);
    std::fs::write(&config_path, serde_json::to_string_pretty(&config)?)?;

    let prefs_path = format!("{}/android-prefs.json", data_dir);
    std::fs::write(&prefs_path, serde_json::to_string_pretty(&config)?)?;

    Ok(())
}