// coulomb-validate: run one scenario JSON against coulomb-core and, in the
// same process, against a Python subprocess that imports
// projects/coulomb-brush/coulomb.py and brush.py. Compare acceptance rate,
// end-of-run energy, and particle count.
//
// Exit non-zero on any comparison outside tolerance.

use coulomb_core::{Brush, Params, Sim};
use serde::{Deserialize, Serialize};
use std::env;
use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};

#[derive(Debug, Deserialize, Serialize, Clone)]
struct StagePoint {
    y: f64,
    x: f64,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
struct BrushCfg {
    #[serde(default = "one")]
    sign: f64,
    #[serde(default = "four")]
    magnitude: f64,
    #[serde(default = "one")]
    density: f64,
    #[serde(default = "ten")]
    thickness: f64,
    #[serde(default = "one")]
    flow: f64,
    #[serde(default = "half")]
    hardness: f64,
    #[serde(default = "one")]
    penetrability: f64,
    #[serde(default = "twelve")]
    coupling: f64,
}
fn one() -> f64 { 1.0 }
fn four() -> f64 { 4.0 }
fn ten() -> f64 { 10.0 }
fn half() -> f64 { 0.5 }
fn twelve() -> f64 { 12.0 }

#[derive(Debug, Deserialize, Serialize, Clone)]
struct Stage {
    temperature: f64,
    iterations: u64,
    #[serde(default)]
    strokes: Vec<Vec<StagePoint>>,
    #[serde(default)]
    brush: Option<BrushCfg>,
    #[serde(default = "fifty")]
    energy_sample_every: u64,
}
fn fifty() -> u64 { 50 }

#[derive(Debug, Deserialize, Serialize, Clone)]
struct Scenario {
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
    #[serde(default = "half")]
    attract_range: f64,
    #[serde(default)]
    periodic: bool,
    #[serde(default = "sixtyfour")]
    batch: usize,
    #[serde(default = "one_us")]
    batch_min: usize,
    #[serde(default = "one_us")]
    batch_decrement: usize,
    #[serde(default = "twelve_us")]
    fail_limit: usize,
    #[serde(default = "two_i32")]
    step_size: i32,
    stages: Vec<Stage>,
    #[serde(default = "one_pct")]
    accept_tol: f64,
    #[serde(default = "one_pct")]
    energy_tol: f64,
    /// Number of independent chains averaged into each stage's statistics.
    /// Independent chains suppress RNG-stream-specific variance in the mean
    /// tail energy by sqrt(chains); the single-snapshot energy would still
    /// carry its full thermal fluctuation.
    #[serde(default = "one_us")]
    chains: usize,
}
fn sixtyfour() -> usize { 64 }
fn one_us() -> usize { 1 }
fn twelve_us() -> usize { 12 }
fn two_i32() -> i32 { 2 }
fn one_pct() -> f64 { 0.01 }

#[derive(Debug, Serialize, Deserialize)]
struct StageResult {
    stage: usize,
    temperature: f64,
    iterations: u64,
    proposed: u64,
    accepted: u64,
    acceptance: f64,
    energy: f64,
    /// Mean of the running energy sampled every `energy_sample_every`
    /// iterations across the second half of the stage. This is the
    /// thermodynamic quantity worth comparing between RNG streams: the
    /// snapshot `energy` has ~sqrt(N)*kT noise on a single trajectory and is
    /// only meaningful for the same-seed same-stream case, which we do not
    /// have (RNG streams differ by construction between Rust and numpy).
    energy_mean_tail: f64,
    particles: usize,
}

#[derive(Debug, Serialize, Deserialize)]
struct RunResult {
    stages: Vec<StageResult>,
    final_energy: f64,
    final_particles: usize,
}

fn run_rust(sc: &Scenario) -> RunResult {
    let chains = sc.chains.max(1);
    // Per-chain fresh sim with a derived seed, so each chain is an
    // independent trajectory sampling the same equilibrium distribution.
    let mut stages_agg: Vec<Vec<StageResult>> = (0..sc.stages.len()).map(|_| Vec::new()).collect();
    let mut final_particles = 0usize;
    let mut final_energy = 0.0f64;
    for chain in 0..chains {
        let seed = sc.seed.wrapping_add(chain as u64 * 997);
        let params = Params {
            h: sc.h,
            w: sc.w,
            seed,
            strength: sc.strength,
            screening: sc.screening,
            cutoff: sc.cutoff,
            periodic: sc.periodic,
            charge: sc.charge,
            attract_depth: sc.attract_depth,
            attract_range: sc.attract_range,
            temperature: sc.stages.first().map(|s| s.temperature).unwrap_or(5000.0),
            batch: sc.batch,
            batch_min: sc.batch_min,
            batch_decrement: sc.batch_decrement,
            fail_limit: sc.fail_limit,
            step_size: sc.step_size,
        };
        let mut sim = Sim::new_blank(params, sc.particles);
        let mut prev_stats = sim.stats();
        for (i, st) in sc.stages.iter().enumerate() {
            sim.set_temperature(st.temperature);
            for stroke in &st.strokes {
                let br_cfg = st.brush.clone().unwrap_or(BrushCfg {
                    sign: 1.0,
                    magnitude: 4.0,
                    density: 1.0,
                    thickness: 10.0,
                    flow: 1.0,
                    hardness: 0.5,
                    penetrability: 1.0,
                    coupling: 12.0,
                });
                let br = Brush {
                    sign: br_cfg.sign,
                    magnitude: br_cfg.magnitude,
                    density: br_cfg.density,
                    thickness: br_cfg.thickness,
                    flow: br_cfg.flow,
                    hardness: br_cfg.hardness,
                    penetrability: br_cfg.penetrability,
                    coupling: br_cfg.coupling,
                };
                let points: Vec<[f64; 2]> = stroke.iter().map(|p| [p.y, p.x]).collect();
                sim.paint_stroke(&points, &br, true);
            }
            let mut history: Vec<f64> = Vec::new();
            for k in 0..st.iterations {
                sim.step();
                if k % st.energy_sample_every == 0 {
                    history.push(sim.stats().energy);
                }
            }
            let tail: &[f64] = &history[history.len() / 2..];
            let energy_mean_tail = if tail.is_empty() {
                sim.stats().energy
            } else {
                tail.iter().sum::<f64>() / tail.len() as f64
            };
            let s = sim.stats();
            let dp = s.proposed.saturating_sub(prev_stats.proposed);
            let da = s.accepted.saturating_sub(prev_stats.accepted);
            stages_agg[i].push(StageResult {
                stage: i,
                temperature: st.temperature,
                iterations: st.iterations,
                proposed: dp,
                accepted: da,
                acceptance: if dp == 0 { 0.0 } else { da as f64 / dp as f64 },
                energy: s.energy,
                energy_mean_tail,
                particles: s.particles,
            });
            prev_stats = s;
        }
        let s = sim.stats();
        final_energy = s.energy;
        final_particles = s.particles;
    }
    // Mean acceptance and mean-tail-energy across chains. Particle count is
    // conserved exactly, so any chain's count is the answer.
    let stages: Vec<StageResult> = stages_agg
        .iter()
        .enumerate()
        .map(|(i, per_chain)| {
            let n = per_chain.len() as u64;
            let sum_prop: u64 = per_chain.iter().map(|r| r.proposed).sum();
            let sum_acc: u64 = per_chain.iter().map(|r| r.accepted).sum();
            let mean_e: f64 = per_chain.iter().map(|r| r.energy_mean_tail).sum::<f64>() / n as f64;
            let snapshot_e: f64 = per_chain.iter().map(|r| r.energy).sum::<f64>() / n as f64;
            StageResult {
                stage: i,
                temperature: per_chain[0].temperature,
                iterations: per_chain[0].iterations,
                proposed: sum_prop,
                accepted: sum_acc,
                acceptance: if sum_prop == 0 { 0.0 } else { sum_acc as f64 / sum_prop as f64 },
                energy: snapshot_e,
                energy_mean_tail: mean_e,
                particles: per_chain[0].particles,
            }
        })
        .collect();
    RunResult { stages, final_energy, final_particles }
}

fn run_python(scenario_path: &PathBuf) -> Result<RunResult, String> {
    // The Python driver sits next to `main.rs` in the repo tree, and is
    // deliberately kept small and Cargo-invocation-agnostic. It only imports
    // projects/coulomb-brush/coulomb.py and brush.py (via a computed sys.path
    // insert), and mirrors the physics list this crate's `Sim` implements.
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    let script = PathBuf::from(manifest_dir).join("py_reference.py");
    if !script.exists() {
        return Err(format!("python reference script missing: {}", script.display()));
    }
    let py = env::var("COULOMB_PY").unwrap_or_else(|_| "python3".to_string());
    let mut child = Command::new(&py)
        .arg(&script)
        .arg(scenario_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("failed to spawn {}: {}", py, e))?;
    let stdout = child.stdout.take().unwrap();
    let out: RunResult = serde_json::from_reader(stdout)
        .map_err(|e| format!("failed to parse python output: {}", e))?;
    let status = child.wait().map_err(|e| format!("python wait: {}", e))?;
    if !status.success() {
        return Err(format!("python reference exited non-zero: {}", status));
    }
    Ok(out)
}

fn compare(sc: &Scenario, rust: &RunResult, py: &RunResult) -> Vec<String> {
    let mut fails = Vec::new();
    if rust.stages.len() != py.stages.len() {
        fails.push(format!(
            "stage count mismatch: rust {} py {}",
            rust.stages.len(),
            py.stages.len()
        ));
        return fails;
    }
    for i in 0..rust.stages.len() {
        let r = &rust.stages[i];
        let p = &py.stages[i];
        let da = (r.acceptance - p.acceptance).abs();
        // For very low acceptance stages (~0), an "absolute 1%" bound is the
        // right one; anywhere else the 1% relative bound sees more signal.
        let acc_ok = da <= sc.accept_tol
            || (p.acceptance > 0.0 && da / p.acceptance <= sc.accept_tol);
        if !acc_ok {
            fails.push(format!(
                "stage {} acceptance mismatch: rust {:.4} py {:.4} (dA {:.4})",
                i, r.acceptance, p.acceptance, da
            ));
        }
        let denom = p.energy_mean_tail.abs().max(1e-30);
        let de_rel = (r.energy_mean_tail - p.energy_mean_tail).abs() / denom;
        if de_rel > sc.energy_tol {
            fails.push(format!(
                "stage {} energy_mean_tail mismatch: rust {:.6e} py {:.6e} (rel {:.4})",
                i, r.energy_mean_tail, p.energy_mean_tail, de_rel
            ));
        }
        if r.particles != p.particles {
            fails.push(format!(
                "stage {} particle count mismatch: rust {} py {}",
                i, r.particles, p.particles
            ));
        }
    }
    if rust.final_particles != py.final_particles {
        fails.push(format!(
            "final particle count mismatch: rust {} py {}",
            rust.final_particles, py.final_particles
        ));
    }
    fails
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() != 2 {
        eprintln!("usage: coulomb-validate <scenario.json>");
        std::process::exit(2);
    }
    let scenario_path = PathBuf::from(&args[1]);
    let raw = std::fs::read_to_string(&scenario_path)
        .expect("could not read scenario file");
    let sc: Scenario = serde_json::from_str(&raw).expect("invalid scenario JSON");
    println!("scenario: {}", sc.name);
    let rust = run_rust(&sc);
    let py = match run_python(&scenario_path) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("python reference failed: {}", e);
            std::process::exit(3);
        }
    };
    println!("stage-by-stage comparison:");
    println!(
        "{:>5} {:>10} {:>10} {:>10} {:>14} {:>14}",
        "stage", "T (K)", "acc rust", "acc py", "<E> rust (eV)", "<E> py (eV)"
    );
    for i in 0..rust.stages.len() {
        let r = &rust.stages[i];
        let p = &py.stages[i];
        println!(
            "{:>5} {:>10.1} {:>10.4} {:>10.4} {:>14.4e} {:>14.4e}",
            i, r.temperature, r.acceptance, p.acceptance,
            r.energy_mean_tail, p.energy_mean_tail
        );
    }
    let fails = compare(&sc, &rust, &py);
    let out = std::io::stdout();
    let mut lock = out.lock();
    if fails.is_empty() {
        writeln!(lock, "pass").unwrap();
        std::process::exit(0);
    } else {
        writeln!(lock, "fail:").unwrap();
        for m in fails {
            writeln!(lock, "  - {}", m).unwrap();
        }
        std::process::exit(1);
    }
}
