use anyhow::{bail, Context, Result};
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

    let priv_bytes = signing_key.to_bytes();
    let pub_bytes = verifying_key.to_bytes();

    let priv_pem = format!(
        "-----BEGIN PRIVATE KEY-----\n{}\n-----END PRIVATE KEY-----\n",
        base64_encode(&priv_bytes)
    );
    let pub_pem = format!(
        "-----BEGIN PUBLIC KEY-----\n{}\n-----END PUBLIC KEY-----\n",
        base64_encode(&pub_bytes)
    );

    std::fs::write(&priv_path, &priv_pem)?;
    std::fs::write(&pub_path, &pub_pem)?;

    Ok((priv_pem, pub_pem))
}

pub fn sign_message(keypair_dir: &str, message: &str) -> Result<String> {
    let (priv_path, _) = keypair_path(keypair_dir);
    let priv_pem = std::fs::read_to_string(&priv_path)
        .context("private key not found")?;
    let priv_bytes = decode_base64_pem(&priv_pem)
        .context("failed to decode private key")?;
    let signing_key = ed25519_dalek::SigningKey::from_bytes(&priv_bytes);
    let signature = signing_key.sign(message.as_bytes());
    Ok(signature.to_bytes().iter().map(|b| format!("{:02x}", b)).collect())
}

fn base64_encode(bytes: &[u8]) -> String {
    const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::new();
    let mut i = 0;
    while i + 3 <= bytes.len() {
        let n = ((bytes[i] as u32) << 16) | ((bytes[i+1] as u32) << 8) | (bytes[i+2] as u32);
        result.push(CHARS[((n >> 18) & 0x3F) as usize] as char);
        result.push(CHARS[((n >> 12) & 0x3F) as usize] as char);
        result.push(CHARS[((n >> 6) & 0x3F) as usize] as char);
        result.push(CHARS[(n & 0x3F) as usize] as char);
        i += 3;
    }
    if bytes.len() - i == 1 {
        let n = (bytes[i] as u32) << 16;
        result.push(CHARS[((n >> 18) & 0x3F) as usize] as char);
        result.push(CHARS[((n >> 12) & 0x3F) as usize] as char);
        result.push('=');
        result.push('=');
    } else if bytes.len() - i == 2 {
        let n = ((bytes[i] as u32) << 16) | ((bytes[i+1] as u32) << 8);
        result.push(CHARS[((n >> 18) & 0x3F) as usize] as char);
        result.push(CHARS[((n >> 12) & 0x3F) as usize] as char);
        result.push(CHARS[((n >> 6) & 0x3F) as usize] as char);
        result.push('=');
    }
    result
}

fn decode_base64_pem(pem: &str) -> Result<[u8; 32]> {
    let b64: String = pem.lines()
        .filter(|l| !l.starts_with("-----"))
        .collect();
    let bytes = base64_decode(&b64)?;
    if bytes.len() != 32 {
        bail!("expected 32-byte key, got {} bytes", bytes.len());
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&bytes);
    Ok(arr)
}

fn base64_decode(input: &str) -> Result<Vec<u8>> {
    const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut lookup = [0u8; 256];
    for (i, &c) in CHARS.iter().enumerate() {
        lookup[c as usize] = i as u8;
    }
    let input = input.trim();
    let mut result = Vec::with_capacity(input.len() * 3 / 4);
    let chars: Vec<u8> = input.bytes().filter(|&c| c != b'\n' && c != b'\r').collect();
    let mut i = 0;
    while i + 4 <= chars.len() {
        let a = lookup[chars[i] as usize] as u32;
        let b = lookup[chars[i+1] as usize] as u32;
        let c = if chars[i+2] == b'=' { 0 } else { lookup[chars[i+2] as usize] as u32 };
        let d = if chars[i+3] == b'=' { 0 } else { lookup[chars[i+3] as usize] as u32 };
        let n = (a << 18) | (b << 12) | (c << 6) | d;
        result.push(((n >> 16) & 0xFF) as u8);
        if chars[i+2] != b'=' { result.push(((n >> 8) & 0xFF) as u8); }
        if chars[i+3] != b'=' { result.push((n & 0xFF) as u8); }
        i += 4;
    }
    Ok(result)
}