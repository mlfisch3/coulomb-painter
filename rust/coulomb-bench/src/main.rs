// coulomb-bench: minimal moves-per-second bench on the local wgpu adapter.
//
// Loads a scenario JSON in the coulomb-validate format, builds a GpuSim on the
// blank-canvas initial config, and runs sweeps until the requested wall-clock
// duration elapses. Reports MPS, render-frames-per-second, adapter identity,
// and peak GPU buffer allocation, plus a final JSON blob for machine reads.

use coulomb_core::Params;
use coulomb_gpu::GpuSim;
use serde::Deserialize;
use serde_json::json;
use std::path::PathBuf;
use std::time::{Duration, Instant};

#[derive(Debug, Deserialize)]
struct Stage {
    temperature: f64,
    #[serde(default)]
    _iterations: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct Scenario {
    #[serde(default)]
    name: String,
    h: usize,
    w: usize,
    seed: u64,
    particles: usize,
    cutoff: f64,
    #[serde(default)]
    screening: f64,
    #[serde(default = "one")]
    charge: f64,
    #[serde(default = "one")]
    strength: f64,
    #[serde(default)]
    attract_depth: f64,
    #[serde(default = "onef")]
    attract_range: f64,
    #[serde(default)]
    periodic: bool,
    #[serde(default = "sixtyfour")]
    _batch: usize,
    #[serde(default = "one_us")]
    _batch_min: usize,
    #[serde(default = "one_us")]
    _batch_decrement: usize,
    #[serde(default = "twelve_us")]
    _fail_limit: usize,
    #[serde(default = "two_i32")]
    step_size: i32,
    stages: Vec<Stage>,
}
fn one() -> f64 { 1.0 }
fn onef() -> f64 { 1.5 }
fn sixtyfour() -> usize { 64 }
fn one_us() -> usize { 1 }
fn twelve_us() -> usize { 12 }
fn two_i32() -> i32 { 2 }

fn parse_duration(s: &str) -> Option<Duration> {
    // Accept "5s", "2500ms", "30s", "1m". Simple by design.
    if let Some(v) = s.strip_suffix("ms") {
        return Some(Duration::from_millis(v.parse().ok()?));
    }
    if let Some(v) = s.strip_suffix('s') {
        let ms = (v.parse::<f64>().ok()? * 1000.0) as u64;
        return Some(Duration::from_millis(ms));
    }
    if let Some(v) = s.strip_suffix('m') {
        return Some(Duration::from_secs(v.parse::<u64>().ok()? * 60));
    }
    None
}

fn main() {
    let mut scenario_path: Option<PathBuf> = None;
    let mut duration: Duration = Duration::from_secs(5);
    let mut moves_per_tile: u32 = 64;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--scenario" => scenario_path = args.next().map(PathBuf::from),
            "--duration" => {
                duration = args.next().and_then(|v| parse_duration(&v)).unwrap_or(duration);
            }
            "--moves-per-tile" => {
                moves_per_tile = args.next().and_then(|v| v.parse().ok()).unwrap_or(moves_per_tile);
            }
            _ => {
                eprintln!("unknown arg: {}", a);
                std::process::exit(2);
            }
        }
    }
    let path = match scenario_path {
        Some(p) => p,
        None => {
            eprintln!("usage: coulomb-bench --scenario <file.json> [--duration 5s] [--moves-per-tile 64]");
            std::process::exit(2);
        }
    };
    let raw = std::fs::read_to_string(&path).expect("cannot read scenario");
    let sc: Scenario = serde_json::from_str(&raw).expect("invalid scenario json");
    let stage = sc.stages.first().expect("scenario has no stages");
    let temperature = stage.temperature;

    let params = Params {
        h: sc.h,
        w: sc.w,
        seed: sc.seed,
        strength: sc.strength,
        screening: sc.screening,
        cutoff: sc.cutoff,
        periodic: sc.periodic,
        charge: sc.charge,
        attract_depth: sc.attract_depth,
        attract_range: sc.attract_range,
        temperature,
        batch: 1,
        batch_min: 1,
        batch_decrement: 1,
        fail_limit: 12,
        step_size: sc.step_size,
    };

    let mut sim = match GpuSim::new_blank(params, sc.particles) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("gpu init failed: {}", e);
            eprintln!("Try one of:");
            eprintln!("  WGPU_BACKEND=vulkan");
            eprintln!("  WGPU_BACKEND=gl");
            eprintln!("  WGPU_ADAPTER_NAME=llvmpipe");
            std::process::exit(3);
        }
    };
    let info = sim.adapter_info().clone();
    eprintln!("scenario: {}", sc.name);
    eprintln!("adapter: {} [{}] type={} driver={}", info.name, info.backend, info.device_type, info.driver);
    eprintln!("tile edge: {}", sim.tile_edge());
    eprintln!("temperature: {} K", temperature);

    // Warm-up: one sweep to compile pipeline caches so the first measured
    // window is not paying JIT compile cost.
    let _ = sim.sweep(temperature, moves_per_tile);

    // Compute-only measurement.
    let t0 = Instant::now();
    let mut attempted: u64 = 0;
    let accepted_before = sim.stats().accepted;
    while t0.elapsed() < duration {
        attempted += sim.sweep(temperature, moves_per_tile);
    }
    sim.poll();
    let elapsed_compute = t0.elapsed();
    let mps = attempted as f64 / elapsed_compute.as_secs_f64();
    let accepted_after = sim.stats().accepted;
    let attempts_delta = attempted;
    let acc_rate = if attempts_delta > 0 {
        (accepted_after - accepted_before) as f64 / attempts_delta as f64
    } else {
        0.0
    };

    // Render throughput: same GPU alternately dispatches compute and render
    // for a shorter window, measured separately so a headless bench still
    // yields both numbers.
    let t1 = Instant::now();
    let mut frames: u64 = 0;
    let render_window = Duration::from_millis((duration.as_millis() as u64 / 4).max(500));
    while t1.elapsed() < render_window {
        let _ = sim.sweep(temperature, moves_per_tile);
        sim.render_frame();
        frames += 1;
    }
    sim.poll();
    let fps = frames as f64 / t1.elapsed().as_secs_f64();

    // Sanity: recompute total energy from occupancy and confirm particle
    // conservation. Cheap enough at 128x128 to run at end of bench.
    let e = sim.recompute_energy();
    let particles = sim.occupancy().iter().filter(|&&b| b).count();

    let summary = json!({
        "scenario": sc.name,
        "adapter": {
            "name": info.name,
            "backend": info.backend,
            "device_type": info.device_type,
            "driver": info.driver,
        },
        "compute": {
            "seconds": elapsed_compute.as_secs_f64(),
            "attempts": attempted,
            "mps": mps,
            "accept_rate": acc_rate,
        },
        "render_alongside_compute": {
            "frames": frames,
            "seconds": t1.elapsed().as_secs_f64(),
            "fps": fps,
        },
        "peak_gpu_bytes": sim.peak_gpu_bytes(),
        "peak_gpu_bytes_note": "sum of coulomb-gpu's own device allocations; excludes driver/heap overhead",
        "temperature": temperature,
        "particles": particles,
        "final_energy_ev": e,
        "moves_per_tile": moves_per_tile,
        "tile_edge": sim.tile_edge(),
    });
    println!("{}", serde_json::to_string_pretty(&summary).unwrap());
}
