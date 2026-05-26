mod tunnel;
mod auth;
mod queue_client;

use jni::objects::JClass;
use jni::objects::JString;
use jni::objects::JObject;
use jni::JNIEnv;
use jni::sys::{jboolean, jint, jstring};

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_init(
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

    match auth::ensure_keypair_android() {
        Ok(_) => {}
        Err(e) => {
            eprintln!("keypair init failed: {e}");
            return -2;
        }
    }

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return -3,
    };

    rt.block_on(async {
        match queue_client::QueueClient::new(&queue_url, &username)
            .fetch_tunnel_certs()
            .await
        {
            Ok(_) => 0,
            Err(e) => {
                eprintln!("tunnel cert fetch failed: {e}");
                -4
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_connect(
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

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return -3,
    };

    let result = rt.block_on(async {
        let client = tunnel::TunnelClient::from_config(&host, port as u16, &worker_id, rpc_port as u16);
        client.run().await
    });

    match result {
        Ok(_) => 0,
        Err(e) => {
            eprintln!("tunnel error: {e}");
            -4
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_getPublicKey(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match auth::get_public_key_pem() {
        Ok(pem) => env.new_string(pem)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_akinus21_akaiagent_TunnelNative_signRequest(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
) -> jstring {
    let msg: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match auth::sign_message(&msg) {
        Ok(sig) => env.new_string(sig)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}