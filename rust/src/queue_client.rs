use anyhow::{bail, Context, Result};
use reqwest::Client;
use serde::Deserialize;

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

    pub async fn fetch_tunnel_certs(&self, cert_dir: &str) -> Result<TunnelCertsResponse> {
        let url = format!("{}/tunnel/certs", self.base_url);
        let key_dir = format!("{}/../keys", cert_dir);
        let (_, public_key) = crate::auth::ensure_keypair_android(&key_dir)?;

        let signature = crate::auth::sign_message(&key_dir, "GET /tunnel/certs")?;

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