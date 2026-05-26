use anyhow::{bail, Context, Result};
use std::path::PathBuf;

pub fn data_dir() -> PathBuf {
    dirs_data_dir().unwrap_or_else(|_| PathBuf::from("/data/local/tmp/akai-agent"))
}

#[cfg(target_os = "android")]
fn dirs_data_dir() -> Result<PathBuf> {
    Ok(PathBuf::from("/data/local/tmp/akai-agent"))
}

#[cfg(not(target_os = "android"))]
fn dirs_data_dir() -> Result<PathBuf> {
    Ok(dirs::data_dir().context("no data dir")?.join("akai-agent"))
}

fn keypair_path() -> (PathBuf, PathBuf) {
    let dir = data_dir();
    (dir.join("worker.key"), dir.join("worker.pub"))
}

pub fn get_public_key_pem() -> Result<String> {
    let (_, pub_path) = keypair_path();
    if !pub_path.exists() {
        bail!("keypair not found — run init first");
    }
    std::fs::read_to_string(&pub_path).context("failed to read public key")
}

pub fn ensure_keypair_android() -> Result<(String, String)> {
    let (priv_path, pub_path) = keypair_path();

    if priv_path.exists() && pub_path.exists() {
        let priv_key = std::fs::read_to_string(&priv_path)?;
        let pub_key = std::fs::read_to_string(&pub_path)?;
        return Ok((priv_key, pub_key));
    }

    let dir = data_dir();
    std::fs::create_dir_all(&dir)?;

    let mut csprng = rand::rngs::OsRng;
    let signing_key = ed25519_dalek::SigningKey::generate(&mut csprng);
    let verifying_key = signing_key.verifying_key();

    let priv_pem = signing_key.to_pkcs8_pem(ed25519_dalek::pkcs8::spki::der::DocumentKind::Private)
        .context("failed to encode private key")?;
    let pub_pem = verifying_key.to_pkcs8_pem()
        .context("failed to encode public key")?;

    std::fs::write(&priv_path, priv_pem.as_str())?;
    std::fs::write(&pub_path, pub_pem.as_str())?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&priv_path, std::fs::Permissions::from_mode(0o600)).ok();
    }

    Ok((priv_pem.to_string(), pub_pem.to_string()))
}

pub fn sign_message(message: &str) -> Result<String> {
    let (priv_path, _) = keypair_path();
    let priv_pem = std::fs::read_to_string(&priv_path)
        .context("private key not found")?;
    let signing_key = ed25519_dalek::SigningKey::from_pkcs8_pem(&priv_pem)
        .context("failed to parse private key")?;
    let signature = signing_key.sign(message.as_bytes());
    Ok(signature.to_bytes().iter().map(|b| format!("{:02x}", b)).collect())
}