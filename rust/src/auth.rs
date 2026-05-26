use anyhow::{bail, Context, Result};
use ed25519_dalek::pkcs8::{DecodePrivateKey, EncodePrivateKey};
use std::path::PathBuf;

fn keypair_path(keypair_dir: &str) -> (PathBuf, PathBuf) {
    let dir = PathBuf::from(keypair_dir);
    (dir.join("worker.key"), dir.join("worker.pub"))
}

pub fn get_public_key_pem(keypair_dir: &str) -> Result<String> {
    let (_, pub_path) = keypair_path(keypair_dir);
    if !pub_path.exists() {
        bail!("keypair not found — run init first");
    }
    std::fs::read_to_string(&pub_path).context("failed to read public key")
}

pub fn ensure_keypair_android(keypair_dir: &str) -> Result<(String, String)> {
    let (priv_path, pub_path) = keypair_path(keypair_dir);

    if priv_path.exists() && pub_path.exists() {
        let priv_key = std::fs::read_to_string(&priv_path)?;
        let pub_key = std::fs::read_to_string(&pub_path)?;
        return Ok((priv_key, pub_key));
    }

    let dir = PathBuf::from(keypair_dir);
    std::fs::create_dir_all(&dir)?;

    let mut csprng = rand::rngs::OsRng;
    let signing_key = ed25519_dalek::SigningKey::generate(&mut csprng);
    let verifying_key = signing_key.verifying_key();

    let priv_pem = signing_key.to_pkcs8_pem(pkcs8::LineEnding::LF)
        .context("failed to encode private key")?;
    let pub_pem = verifying_key.to_public_key_pem(pkcs8::LineEnding::LF)
        .context("failed to encode public key")?;

    std::fs::write(&priv_path, priv_pem.as_str())?;
    std::fs::write(&pub_path, pub_pem.as_str())?;

    Ok((priv_pem.to_string(), pub_pem.to_string()))
}

pub fn sign_message(keypair_dir: &str, message: &str) -> Result<String> {
    let (priv_path, _) = keypair_path(keypair_dir);
    let priv_pem = std::fs::read_to_string(&priv_path)
        .context("private key not found")?;
    let signing_key = ed25519_dalek::SigningKey::from_pkcs8_pem(&priv_pem)
        .context("failed to parse private key")?;
    let signature = signing_key.sign(message.as_bytes());
    Ok(signature.to_bytes().iter().map(|b| format!("{:02x}", b)).collect())
}