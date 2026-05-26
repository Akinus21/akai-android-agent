use anyhow::{Context, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::io::Cursor;

#[derive(Debug, Deserialize)]
struct AuthResponse {
    #[serde(default)]
    pub detail: Option<String>,
    #[serde(default)]
    pub wg_ip: Option<String>,
    #[serde(default)]
    pub peer_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TunnelCertsResponse {
    pub ca_cert: String,
    pub worker_cert: String,
    pub worker_key: String,
    pub tunnel_host: String,
    pub tunnel_port: u16,
}

pub struct QueueClient {
    base_url: String,
    username: String,
    client: Client,
}

impl QueueClient {
    pub fn new(base_url: &str, username: &str) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            username: username.to_string(),
            client: Client::builder()
                .danger_accept_invalid_certs(true)
                .build()
                .unwrap_or_default(),
        }
    }

    pub async fn fetch_tunnel_certs(&self) -> Result<TunnelCertsResponse> {
        let url = format!("{}/tunnel/certs", self.base_url);
        let (_, public_key) = crate::auth::ensure_keypair_android()?;

        let signature = crate::auth::sign_message("GET /tunnel/certs")?;

        let resp = self.client.get(&url)
            .header("X-Worker-Key", public_key.trim())
            .header("X-Worker-Sig", signature)
            .header("X-Akai-Username", &self.username)
            .send()
            .await
            .context("failed to fetch tunnel certs")?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            bail!("tunnel certs request failed: {} - {}", status, body);
        }

        let certs: TunnelCertsResponse = resp.json().await
            .context("failed to parse tunnel certs response")?;

        let cert_dir = crate::auth::data_dir().join("tunnel-certs");
        std::fs::create_dir_all(&cert_dir)
            .context("failed to create tunnel-certs directory")?;

        std::fs::write(cert_dir.join("ca.crt"), &certs.ca_cert)?;
        std::fs::write(cert_dir.join("worker.crt"), &certs.worker_cert)?;
        std::fs::write(cert_dir.join("worker.key"), &certs.worker_key)?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(cert_dir.join("worker.key"), std::fs::Permissions::from_mode(0o600)).ok();
        }

        Ok(certs)
    }
}