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
                    alog!(ERROR, "error chain:");
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

    pub async fn fetch_tunnel_certs(&self, cert_dir: &str) -> Result<TunnelCertsResponse> {
        let url = format!("{}/tunnel/certs", self.base_url);
        alog!(INFO, "fetching tunnel certs from {}", url);
        let key_dir = format!("{}/../keys", cert_dir);
        let _ = crate::auth::ensure_keypair_android(&key_dir)?;

        let timestamp = crate::auth::timestamp_millis();
        let signature = crate::auth::sign_request(&key_dir, &timestamp, "GET", "/tunnel/certs", b"")?;

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

        std::fs::create_dir_all(cert_dir)
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