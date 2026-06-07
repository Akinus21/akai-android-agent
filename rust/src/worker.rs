use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tracing::{info, error};

fn encode_msg(msg: &HubMessage) -> Vec<u8> {
    let mut data = serde_json::to_vec(msg).unwrap_or_default();
    data.push(b'\n');
    data
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerInfo {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub layer_offset: usize,
    #[serde(default)]
    pub num_layers: usize,
    #[serde(default)]
    pub vram_gb: f32,
    #[serde(default)]
    pub has_gpu: bool,
    #[serde(default)]
    pub wg_ip: String,
    #[serde(default)]
    pub rpc_port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HopInfo {
    pub worker_id: String,
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineWorker {
    pub worker_id: String,
    pub layer_offset: usize,
    pub num_layers: usize,
    pub last_hop: Option<HopInfo>,
    pub next_hop: Option<HopInfo>,
    #[serde(default)]
    pub is_first: bool,
    #[serde(default)]
    pub is_last: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineInfo {
    pub pipeline_id: String,
    pub workers: Vec<PipelineWorker>,
    pub model_name: String,
    pub model_url: String,
    #[serde(default)]
    pub num_layers: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerHeartbeat {
    pub worker_id: String,
    pub load: f32,
    pub layer_offset: usize,
    pub num_layers: usize,
    pub has_gpu: bool,
    pub vram_gb: f32,
    pub active: bool,
    #[serde(default)]
    pub last_hop_connected: bool,
    #[serde(default)]
    pub next_hop_connected: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeartbeatResponse {
    pub layer_offset: usize,
    pub num_layers: usize,
    #[serde(default)]
    pub reassign: bool,
    #[serde(default)]
    pub model_name: String,
    #[serde(default)]
    pub model_url: String,
    #[serde(default)]
    pub model_hash: String,
    #[serde(default)]
    pub pipeline: Option<PipelineInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceRequest {
    pub id: String,
    #[serde(default)]
    pub tokens: Vec<i64>,
    #[serde(default)]
    pub is_first: bool,
    #[serde(default)]
    pub is_last: bool,
    #[serde(default)]
    pub max_new_tokens: usize,
    #[serde(default)]
    pub temperature: f32,
    #[serde(default)]
    pub prompt: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceResponse {
    pub id: String,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub prompt_tokens: usize,
    #[serde(default)]
    pub completion_tokens: usize,
    #[serde(default)]
    pub is_done: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceForward {
    pub id: String,
    pub from_worker: String,
    pub to_worker: String,
    #[serde(default)]
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum HubMessage {
    #[serde(rename = "register")]
    Register(WorkerInfo),
    #[serde(rename = "inference_request")]
    InferenceRequest(InferenceRequest),
    #[serde(rename = "inference_response")]
    InferenceResponse(InferenceResponse),
    #[serde(rename = "inference_forward")]
    InferenceForward(InferenceForward),
    #[serde(rename = "heartbeat")]
    Heartbeat(WorkerHeartbeat),
    #[serde(rename = "heartbeat_response")]
    HeartbeatResponse(HeartbeatResponse),
    #[serde(rename = "heartbeat_forward")]
    HeartbeatForward { pipeline: PipelineInfo },
}

pub struct WorkerConfig {
    pub hub_addr: String,
    pub worker_id: String,
    pub has_gpu: bool,
    pub vram_gb: f32,
    pub rpc_port: u16,
}

pub async fn run_worker(config: WorkerConfig) -> Result<()> {
    info!("Akai-Net Hub Worker starting...");
    info!("  Hub: {}", config.hub_addr);
    info!("  Worker ID: {}", config.worker_id);
    info!("  GPU: {}, VRAM: {:.1} GB", config.has_gpu, config.vram_gb);

    let worker_info = WorkerInfo {
        id: config.worker_id.clone(),
        name: String::new(),
        layer_offset: 0,
        num_layers: 0,
        vram_gb: config.vram_gb,
        has_gpu: config.has_gpu,
        wg_ip: String::new(),
        rpc_port: config.rpc_port,
    };

    loop {
        match TcpStream::connect(&config.hub_addr).await {
            Ok(stream) => {
                info!("Connected to hub at {}", config.hub_addr);

                let (reader, writer) = stream.into_split();
                let reader = Arc::new(Mutex::new(reader));
                let writer = Arc::new(Mutex::new(writer));

                let register = HubMessage::Register(worker_info.clone());
                let data = encode_msg(&register);
                {
                    let mut w = writer.lock().await;
                    w.write_all(&data).await?;
                }
                info!("Registered with hub, maintaining persistent connection...");

                let mut read_buf = Vec::new();
                let mut tmp = [0u8; 65536];

                loop {
                    let n = {
                        let mut r = reader.lock().await;
                        match r.read(&mut tmp).await {
                            Ok(0) => {
                                info!("Hub connection closed, reconnecting...");
                                break;
                            }
                            Ok(n) => n,
                            Err(e) => {
                                error!("Read error: {}", e);
                                break;
                            }
                        }
                    };

                    read_buf.extend_from_slice(&tmp[..n]);

                    while let Some(pos) = read_buf.iter().position(|&b| b == b'\n') {
                        let line: Vec<u8> = read_buf.drain(..=pos).collect();
                        let line = &line[..line.len().saturating_sub(1)];

                        if line.is_empty() {
                            continue;
                        }

                        let msg: HubMessage = match serde_json::from_slice(line) {
                            Ok(m) => m,
                            Err(e) => {
                                error!("Failed to parse message: {}", e);
                                continue;
                            }
                        };

                        match msg {
                            HubMessage::HeartbeatResponse(resp) => {
                                info!("[<- hub] HeartbeatResponse: layers {}-{}, model={}, pipeline={}",
                                    resp.layer_offset, resp.layer_offset + resp.num_layers,
                                    resp.model_name, resp.pipeline.as_ref().map(|p| p.workers.len()).unwrap_or(0));

                                if let Some(pipeline) = resp.pipeline {
                                    if let Some(my_worker) = pipeline.workers.iter().find(|w| w.worker_id == worker_info.id) {
                                        let heartbeat = WorkerHeartbeat {
                                            worker_id: worker_info.id.clone(),
                                            load: 0.0,
                                            layer_offset: my_worker.layer_offset,
                                            num_layers: my_worker.num_layers,
                                            has_gpu: config.has_gpu,
                                            vram_gb: config.vram_gb,
                                            active: true,
                                            last_hop_connected: my_worker.last_hop.is_some(),
                                            next_hop_connected: my_worker.next_hop.is_some(),
                                        };
                                        let msg = HubMessage::Heartbeat(heartbeat);
                                        let data = encode_msg(&msg);
                                        let mut w = writer.lock().await;
                                        w.write_all(&data).await.ok();
                                        info!("[-> hub] Heartbeat: layers {}-{}, active=true",
                                            my_worker.layer_offset, my_worker.layer_offset + my_worker.num_layers);
                                    }
                                }
                            }
                            HubMessage::HeartbeatForward { pipeline } => {
                                info!("[<- hub] HeartbeatForward: pipeline_id={}, {} workers, model={}",
                                    pipeline.pipeline_id, pipeline.workers.len(), pipeline.model_name);

                                if let Some(my_worker) = pipeline.workers.iter().find(|w| w.worker_id == worker_info.id) {
                                    let heartbeat = WorkerHeartbeat {
                                        worker_id: worker_info.id.clone(),
                                        load: 0.0,
                                        layer_offset: my_worker.layer_offset,
                                        num_layers: my_worker.num_layers,
                                        has_gpu: config.has_gpu,
                                        vram_gb: config.vram_gb,
                                        active: true,
                                        last_hop_connected: my_worker.last_hop.is_some(),
                                        next_hop_connected: my_worker.next_hop.is_some(),
                                    };
                                    let msg = HubMessage::Heartbeat(heartbeat);
                                    let data = encode_msg(&msg);
                                    let mut w = writer.lock().await;
                                    w.write_all(&data).await.ok();
                                }
                            }
                            HubMessage::InferenceRequest(req) => {
                                info!("[<- hub] InferenceRequest: id={}, max_tokens={}", req.id, req.max_new_tokens);
                                let resp = InferenceResponse {
                                    id: req.id,
                                    text: Some("ok".to_string()),
                                    prompt_tokens: 0,
                                    completion_tokens: 1,
                                    is_done: true,
                                };
                                let msg = HubMessage::InferenceResponse(resp);
                                let data = encode_msg(&msg);
                                let mut w = writer.lock().await;
                                w.write_all(&data).await.ok();
                            }
                            HubMessage::InferenceForward(fwd) => {
                                info!("[<- hub] InferenceForward: {} -> {}, {} bytes",
                                    fwd.from_worker, fwd.to_worker, fwd.data.len());
                            }
                            HubMessage::Heartbeat { .. } => {}
                            _ => {}
                        }
                    }
                }
            }
            Err(e) => {
                error!("Failed to connect to hub: {}", e);
            }
        }

        tokio::time::sleep(std::time::Duration::from_secs(5)).await;
    }
}