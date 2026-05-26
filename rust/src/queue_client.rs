use anyhow::{bail, Context, Result};
use crate::alog;
use reqwest::Client;
use serde::Deserialize;
use std::error::Error;
use std::sync::OnceLock;

static CLIENT: OnceLock<Client> = OnceLock::new();

fn get_client() -> &'static Client {
    CLIENT.get_or_init(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
        match Client::builder().build() {
            Ok(c) => {
                alog!(INFO, "reqwest client built successfully");
                c
            }
            Err(e) => {
                alog!(ERROR, "failed to build reqwest client: {e}");
                let mut source = e.source();
                while let Some(cause) = source {
                    alog!(ERROR, "  caused by: {cause}");
                    source = cause.source();
                }
                panic!("failed to build reqwest client");
            }
        }
    })
}

#[derive(Debug, Deserialize)]
pub struct TunnelCertsResponse {
    pub ca_cert: String,
    pub worker_cert: String,
    pub worker_key: String,
    pub tunnel_host: String,
    pub tunnel_port: u16,
}

pub struct QueueClient {
    base_url: String,
    username: String,
}

impl QueueClient {
    pub fn new(base_url: &str, username: &str) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            username: username.to_string(),
        }
    }

    pub async fn register_and_fetch_certs(&self, cert_dir: &str, worker_name: &str) -> Result<TunnelCertsResponse> {
        let key_dir = format!("{}/../keys", cert_dir);
        crate::auth::ensure_keypair_android(&key_dir)?;
        let public_key_b64 = crate::auth::get_public_key_base64(&key_dir)?;

        self.register_device(&key_dir, worker_name, &public_key_b64).await?;
        self.fetch_tunnel_certs(&key_dir).await
    }

    async fn register_device(&self, key_dir: &str, worker_name: &str, public_key_b64: &str) -> Result<()> {
        let url = format!("{}/auth/register", self.base_url);
        alog!(INFO, "registering device at {}", url);

        let body = serde_json::json!({
            "username": self.username,
            "worker_name": worker_name,
            "public_key": public_key_b64,
        });
        let body_bytes = serde_json::to_vec(&body)?;

        let timestamp = crate::auth::timestamp_millis();
        let signature = crate::auth::sign_request(key_dir, &timestamp, "POST", "/auth/register", &body_bytes)?;

        let client = get_client();
        let resp = client.post(&url)
            .header("X-Akai-Username", &self.username)
            .header("X-Akai-Device-Id", worker_name)
            .header("X-Akai-Timestamp", &timestamp)
            .header("X-Akai-Signature", &signature)
            .header("Content-Type", "application/json")
            .body(body_bytes.clone())
            .send()
            .await
            .context("register request failed")?;

        if resp.status().is_success() {
            alog!(INFO, "device registered successfully");
            return Ok(());
        }

        let status = resp.status();
        let body_text = resp.text().await.unwrap_or_default();
        alog!(INFO, "register returned {} (may need Duo): {}", status, body_text);

        if body_text.contains("Duo") || body_text.contains("2FA") {
            alog!(INFO, "triggering Duo push for {}", self.username);
            let duo_url = format!("{}/auth/duo", self.base_url);
            let duo_body = serde_json::json!({
                "username": self.username,
                "worker_name": worker_name,
                "public_key": public_key_b64,
            });

            let duo_resp = client.post(&duo_url)
                .header("Content-Type", "application/json")
                .json(&duo_body)
                .send()
                .await
                .context("duo request failed")?;

            if duo_resp.status().is_success() {
                alog!(INFO, "Duo approved, device registered");
                return Ok(());
            }

            let duo_status = duo_resp.status();
            let duo_text = duo_resp.text().await.unwrap_or_default();
            bail!("Duo auth failed: {} - {}", duo_status, duo_text);
        }

        bail!("register failed: {} - {}", status, body_text)
    }

    async fn fetch_tunnel_certs(&self, key_dir: &str) -> Result<TunnelCertsResponse> {
        let cert_dir = format!("{}/../tunnel-certs", key_dir);
        let url = format!("{}/tunnel/certs", self.base_url);
        alog!(INFO, "fetching tunnel certs from {}", url);

        let timestamp = crate::auth::timestamp_millis();
        let signature = crate::auth::sign_request(key_dir, &timestamp, "GET", "/tunnel/certs", b"")?;

        let client = get_client();
        let resp = match client.get(&url)
            .header("X-Akai-Username", &self.username)
            .header("X-Akai-Device-Id", &self.username)
            .header("X-Akai-Timestamp", &timestamp)
            .header("X-Akai-Signature", &signature)
            .send()
            .await {
            Ok(r) => r,
            Err(e) => {
                alog!(ERROR, "request to {} failed: {e}", url);
                let mut source = e.source();
                while let Some(cause) = source {
                    alog!(ERROR, "  caused by: {cause}");
                    source = cause.source();
                }
                bail!("request to {} failed: {e}", url);
            }
        };

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            alog!(ERROR, "tunnel certs request failed: {} - {}", status, body);
            bail!("tunnel certs request failed: {} - {}", status, body);
        }

        let certs: TunnelCertsResponse = resp.json().await
            .context("failed to parse tunnel certs response")?;

        std::fs::create_dir_all(&cert_dir)
            .context("failed to create tunnel-certs directory")?;

        std::fs::write(format!("{}/ca.crt", cert_dir), &certs.ca_cert)?;
        std::fs::write(format!("{}/worker.crt", cert_dir), &certs.worker_cert)?;
        std::fs::write(format!("{}/worker.key", cert_dir), &certs.worker_key)?;

        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(
            format!("{}/worker.key", cert_dir),
            std::fs::Permissions::from_mode(0o600),
        ).ok();

        Ok(certs)
    }
}