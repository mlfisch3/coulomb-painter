// coulomb-gpu: wgpu tiled Metropolis kernel for the Coulomb annealer physics.
//
// This is the M2 GPU port of the CPU reference in coulomb-core. It exists so
// M3's Android app can dispatch the same physics on the phone's own Adreno /
// Mali GPU. The port is deliberately validated on the desktop first (this
// crate + coulomb-bench + coulomb-validate) before any NDK toolchain enters
// the picture - a correctness bug diagnosed on lavapipe or a discrete adapter
// is many times cheaper than one that only surfaces on hardware.
//
// The public shape matches coulomb-core's Sim so the app layer can swap
// engines behind a common trait later. `step` on this crate advances a
// "target attempt count" rather than a single move because the GPU's unit of
// work is a sweep over one colour of tiles; that unit is documented on the
// method.

#![deny(unsafe_code)]

use bytemuck::{Pod, Zeroable};
use coulomb_core::{Params, Stats, KB_EV};
use rand::{Rng, SeedableRng};
use rand_pcg::Pcg64Mcg;
use std::borrow::Cow;
use thiserror::Error;
use wgpu::util::DeviceExt;

#[derive(Debug, Error)]
pub enum GpuError {
    #[error("no wgpu adapter matched request; try WGPU_BACKEND=gl or WGPU_ADAPTER_NAME=llvmpipe")]
    AdapterNotFound,
    #[error("wgpu device request failed: {0}")]
    DeviceRequest(String),
}

/// Description of the adapter the sim ran on. Included in bench output so a
/// number is not stripped of its context.
#[derive(Debug, Clone)]
pub struct AdapterInfo {
    pub name: String,
    pub backend: String,
    pub driver: String,
    pub device_type: String,
}

pub struct GpuSim {
    params: Params,
    // The GPU sim keeps a shadow CPU rng that seeds each sweep - the tile
    // offsets and per-sweep kernel seed are drawn from it so a run is
    // reproducible given `params.seed`.
    rng: Pcg64Mcg,
    device: wgpu::Device,
    queue: wgpu::Queue,
    adapter_info: AdapterInfo,

    // Persistent GPU state.
    occ_buf: wgpu::Buffer,
    blocked_buf: wgpu::Buffer,
    u_edge_buf: wgpu::Buffer,
    off_dy_buf: wgpu::Buffer,
    off_dx_buf: wgpu::Buffer,
    off_v_buf: wgpu::Buffer,
    tile_y_buf: wgpu::Buffer,
    tile_x_buf: wgpu::Buffer,
    params_uniform: wgpu::Buffer,
    accepted_buf: wgpu::Buffer,
    attempted_buf: wgpu::Buffer,
    energy_delta_buf: wgpu::Buffer,
    readback_acc: wgpu::Buffer,
    readback_att: wgpu::Buffer,
    readback_de: wgpu::Buffer,
    readback_occ: wgpu::Buffer,

    // Render pipeline (headless offscreen; M3 attaches a real surface here).
    #[allow(dead_code)]
    render_params_uniform: wgpu::Buffer,
    render_pipeline: wgpu::RenderPipeline,
    render_bind_group: wgpu::BindGroup,
    render_target: wgpu::Texture,

    // Compute pipeline and bind group.
    compute_pipeline: wgpu::ComputePipeline,
    compute_bgl: wgpu::BindGroupLayout,

    // Interaction table (from coulomb-core kernel formulation, precomputed).
    noff: u32,
    max_tiles: u32,

    // Sizing.
    tile_edge: u32,
    rc: u32,

    // Cached shadow of CPU-visible state.
    energy: f64,
    accepted: u64,
    attempted: u64,
    iteration: u64,
    peak_bytes: u64,
    particles: usize,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct GpuParams {
    h: u32,
    w: u32,
    l_edge: u32,
    rc: u32,
    hop: u32,
    ntiles: u32,
    noff: u32,
    moves: u32,
    periodic: u32,
    seed: u32,
    q: f32,
    beta: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct RenderParams {
    w: u32,
    h: u32,
    _pad0: u32,
    _pad1: u32,
}

/// Chosen adapter selection knobs. Environment variables the desktop operator
/// can pin (documented in the crate README):
/// - `WGPU_BACKEND` = vulkan | metal | dx12 | gl
/// - `WGPU_ADAPTER_NAME` = substring match on `AdapterInfo::name`
fn pick_adapter(instance: &wgpu::Instance) -> Result<wgpu::Adapter, GpuError> {
    let backends = match std::env::var("WGPU_BACKEND").ok().as_deref() {
        Some("vulkan") => wgpu::Backends::VULKAN,
        Some("metal") => wgpu::Backends::METAL,
        Some("dx12") => wgpu::Backends::DX12,
        Some("gl") => wgpu::Backends::GL,
        Some(_) | None => wgpu::Backends::PRIMARY | wgpu::Backends::GL,
    };
    let want_name = std::env::var("WGPU_ADAPTER_NAME").ok();
    let mut adapters: Vec<wgpu::Adapter> = instance.enumerate_adapters(backends);
    if adapters.is_empty() {
        return Err(GpuError::AdapterNotFound);
    }
    if let Some(sub) = &want_name {
        adapters.retain(|a| a.get_info().name.to_lowercase().contains(&sub.to_lowercase()));
        if adapters.is_empty() {
            return Err(GpuError::AdapterNotFound);
        }
    }
    // Prefer discrete, then integrated, then virtual, then cpu.
    adapters.sort_by_key(|a| match a.get_info().device_type {
        wgpu::DeviceType::DiscreteGpu => 0,
        wgpu::DeviceType::IntegratedGpu => 1,
        wgpu::DeviceType::VirtualGpu => 2,
        wgpu::DeviceType::Cpu => 3,
        wgpu::DeviceType::Other => 4,
    });
    Ok(adapters.into_iter().next().unwrap())
}

fn init_wgpu() -> Result<(wgpu::Device, wgpu::Queue, AdapterInfo), GpuError> {
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends: wgpu::Backends::PRIMARY | wgpu::Backends::GL,
        ..Default::default()
    });
    let adapter = pick_adapter(&instance)?;
    let info = adapter.get_info();
    let ainfo = AdapterInfo {
        name: info.name.clone(),
        backend: format!("{:?}", info.backend),
        driver: info.driver.clone(),
        device_type: format!("{:?}", info.device_type),
    };
    // The kernel binds 11 storage buffers (occ, blocked, u_edge, tile_y/x,
    // off_dy/dx/v, three counter/output arrays), which is above the
    // downlevel_defaults max of 4. The adapter's own limits report what it
    // can honour; on lavapipe / real desktop / Android GPUs that number is
    // >= 8 in every case we care about for M2. If a real Android device
    // reports fewer, M3 refactors the shader to pack the read-only tables
    // into one storage array with offsets.
    let mut limits = adapter.limits();
    limits.max_storage_buffers_per_shader_stage =
        limits.max_storage_buffers_per_shader_stage.max(12);
    let (device, queue) = pollster::block_on(adapter.request_device(
        &wgpu::DeviceDescriptor {
            label: Some("coulomb-gpu"),
            required_features: wgpu::Features::empty(),
            required_limits: limits,
        },
        None,
    ))
    .map_err(|e| GpuError::DeviceRequest(e.to_string()))?;
    Ok((device, queue, ainfo))
}

// Choose a tile edge L such that L > rc and (h,w) can host a useful number of
// tiles. The CUDA reference default is max(rc+1, 16) with an explicit user
// override; we do the same and let the caller substitute at construction time.
fn choose_tile_edge(rc: u32) -> u32 {
    (rc + 1).max(16)
}

// The interaction offset table replicated on CPU so `total_energy` can
// recompute the energy exactly the way the kernel accumulates dE (single-
// precision, same offset list). This is what the GPU's own energy tally is
// consistent with, so the CPU-side sanity check does not accumulate a bias.
struct OffsetTable {
    dy: Vec<i32>,
    dx: Vec<i32>,
    v: Vec<f32>,
}

fn build_offset_table(params: &Params) -> OffsetTable {
    let rc = params.cutoff.ceil().max(1.0) as i32;
    let cutoff = params.cutoff;
    let strength = params.strength;
    let screening = params.screening;
    let depth = params.attract_depth;
    let range = params.attract_range;
    let mut dy = Vec::new();
    let mut dx = Vec::new();
    let mut v = Vec::new();
    for iy in -rc..=rc {
        for ix in -rc..=rc {
            let r = ((iy * iy + ix * ix) as f64).sqrt();
            if r <= 0.0 || r > cutoff {
                continue;
            }
            let mut vv = strength / r;
            if screening > 0.0 {
                vv *= (-r / screening).exp();
            }
            if depth != 0.0 && range > 0.0 {
                vv -= depth * (-r / range).exp();
            }
            dy.push(iy);
            dx.push(ix);
            v.push(vv as f32);
        }
    }
    OffsetTable { dy, dx, v }
}

fn total_energy_from_offsets(
    occ: &[bool],
    u_edge: &[f32],
    off: &OffsetTable,
    h: usize,
    w: usize,
    periodic: bool,
    charge: f64,
) -> f64 {
    // Mirror the CUDA/GPU accumulator: pair term summed over the same offset
    // list, plus the u_edge integral. Runs on the CPU side once at startup and
    // whenever validation asks; the GPU never recomputes total energy, only
    // deltas, so this is the trusted zero point.
    let q = charge;
    let mut e_edge = 0.0f64;
    for i in 0..occ.len() {
        if occ[i] {
            e_edge += u_edge[i] as f64;
        }
    }
    let mut e_mm = 0.0f64;
    let hi = h as i32;
    let wi = w as i32;
    for y in 0..hi {
        for x in 0..wi {
            if !occ[(y as usize) * w + x as usize] {
                continue;
            }
            for (k, (&dy, &dx)) in off.dy.iter().zip(off.dx.iter()).enumerate() {
                let mut yy = y + dy;
                let mut xx = x + dx;
                if periodic {
                    yy = ((yy % hi) + hi) % hi;
                    xx = ((xx % wi) + wi) % wi;
                } else if yy < 0 || yy >= hi || xx < 0 || xx >= wi {
                    continue;
                }
                if occ[(yy as usize) * w + xx as usize] {
                    e_mm += off.v[k] as f64;
                }
            }
        }
    }
    // Each pair counted twice above -> divide by 2. Then times q^2, plus edge.
    0.5 * q * q * e_mm + q * e_edge
}

impl GpuSim {
    /// Convenience: build a sim on a blank canvas of h*w with n mobile
    /// particles placed on evenly-strided flat lattice indices. Uses the same
    /// deterministic placement as `coulomb_core::Sim::new_blank`, so the CPU
    /// and GPU engines start from a bit-identical initial configuration.
    pub fn new_blank(params: Params, n_particles: usize) -> Result<Self, GpuError> {
        let h = params.h;
        let w = params.w;
        let total = h * w;
        let take = n_particles.min(total);
        let mut occ = vec![false; total];
        if take > 0 {
            let stride_num = total as u64;
            let stride_den = take as u64;
            for k in 0..take {
                let idx = ((k as u64 * stride_num) / stride_den) as usize;
                occ[idx] = true;
            }
        }
        // Blank canvas: no fixed field, no blocked sites.
        let blocked = vec![false; total];
        let u_edge = vec![0.0f32; total];
        Self::from_state(params, occ, blocked, u_edge)
    }

    /// Build a sim from an existing occupancy + blocked mask + u_edge field.
    /// Mirrors `coulomb_core::Sim::new`; the u_edge rebuild + blocked mask
    /// composition are done by the CPU core so both engines see bit-identical
    /// inputs at t=0. The desktop version explicitly retired its GPU FFT for
    /// u_edge rebuild (see projects/coulomb-brush/gpu_backend.py:444), so
    /// keeping that work on the CPU side is the reference behaviour.
    pub fn new(
        params: Params,
        cov: Vec<f64>,
        occ0: Vec<bool>,
        line_density: f64,
        line_blocks: bool,
    ) -> Result<Self, GpuError> {
        let sim = coulomb_core::Sim::new(params.clone(), cov, occ0, line_density, line_blocks);
        let occ = sim.occupancy().to_vec();
        let blocked = sim.blocked().to_vec();
        let u_edge: Vec<f32> = sim.u_edge().iter().map(|&x| x as f32).collect();
        Self::from_state(params, occ, blocked, u_edge)
    }

    fn from_state(
        params: Params,
        occ: Vec<bool>,
        blocked: Vec<bool>,
        u_edge: Vec<f32>,
    ) -> Result<Self, GpuError> {
        assert_eq!(occ.len(), params.h * params.w);
        assert_eq!(blocked.len(), params.h * params.w);
        assert_eq!(u_edge.len(), params.h * params.w);

        let (device, queue, adapter_info) = init_wgpu()?;

        let rc = params.cutoff.ceil().max(1.0) as u32;
        let tile_edge = choose_tile_edge(rc);
        let off = build_offset_table(&params);
        let noff = off.dy.len() as u32;

        // Upper bound on tiles per colour under any offset. For non-periodic
        // boundaries a colour has ceil(h/(2L)) * ceil(w/(2L)) tiles at most;
        // give the buffers a small headroom so a launch that lays down one
        // extra edge tile does not require a reallocation.
        let ntiles_per_colour = {
            let n_y = (params.h as u32).div_ceil(2 * tile_edge) + 1;
            let n_x = (params.w as u32).div_ceil(2 * tile_edge) + 1;
            n_y * n_x
        };
        let ntiles_max = ntiles_per_colour.max(1);

        let occ_u32: Vec<u32> = occ.iter().map(|&b| if b { 1 } else { 0 }).collect();
        let blocked_u32: Vec<u32> = blocked.iter().map(|&b| if b { 1 } else { 0 }).collect();

        let occ_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("occ"),
            contents: bytemuck::cast_slice(&occ_u32),
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
        });
        let blocked_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("blocked"),
            contents: bytemuck::cast_slice(&blocked_u32),
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        });
        let u_edge_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("u_edge"),
            contents: bytemuck::cast_slice(&u_edge),
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
        });
        let off_dy_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("off_dy"),
            contents: bytemuck::cast_slice(&off.dy),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let off_dx_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("off_dx"),
            contents: bytemuck::cast_slice(&off.dx),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let off_v_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("off_v"),
            contents: bytemuck::cast_slice(&off.v),
            usage: wgpu::BufferUsages::STORAGE,
        });

        let tile_y_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("tile_y"),
            size: (ntiles_max as u64) * 4,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let tile_x_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("tile_x"),
            size: (ntiles_max as u64) * 4,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let params_uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("params_uniform"),
            size: std::mem::size_of::<GpuParams>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let counter_bytes = (ntiles_max as u64) * 4;
        let de_bytes = (ntiles_max as u64) * 4;
        let accepted_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("accepted"),
            size: counter_bytes,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let attempted_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("attempted"),
            size: counter_bytes,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let energy_delta_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("energy_delta"),
            size: de_bytes,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let readback_acc = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback_acc"),
            size: counter_bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let readback_att = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback_att"),
            size: counter_bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let readback_de = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback_de"),
            size: de_bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let occ_bytes = (occ_u32.len() as u64) * 4;
        let readback_occ = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback_occ"),
            size: occ_bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let peak_bytes = occ_bytes * 2
            + (blocked_u32.len() as u64) * 4
            + (u_edge.len() as u64) * 4
            + (off.dy.len() as u64) * 4 * 3
            + counter_bytes * 3
            + de_bytes * 2
            + (ntiles_max as u64) * 4 * 2
            + std::mem::size_of::<GpuParams>() as u64;

        // Compute pipeline.
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("metropolis.wgsl"),
            source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(include_str!("shaders/metropolis.wgsl"))),
        });
        let compute_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("compute_bgl"),
            entries: &[
                bgl_uniform(0),
                bgl_storage(1, false),
                bgl_storage(2, true),
                bgl_storage(3, true),
                bgl_storage(4, true),
                bgl_storage(5, true),
                bgl_storage(6, true),
                bgl_storage(7, true),
                bgl_storage(8, true),
                bgl_storage(9, false),
                bgl_storage(10, false),
                bgl_storage(11, false),
            ],
        });
        let compute_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("compute_pl"),
            bind_group_layouts: &[&compute_bgl],
            push_constant_ranges: &[],
        });
        let compute_pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("tile_metropolis"),
            layout: Some(&compute_layout),
            module: &shader,
            entry_point: "tile_metropolis",
        });

        // Render pipeline: samples occ + renders to an offscreen RGBA8 target.
        // M3 will substitute a swapchain view; the pipeline itself is stable.
        let rshader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("render.wgsl"),
            source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(include_str!("shaders/render.wgsl"))),
        });
        let render_params_uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("render_params"),
            size: std::mem::size_of::<RenderParams>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        queue.write_buffer(
            &render_params_uniform,
            0,
            bytemuck::bytes_of(&RenderParams {
                w: params.w as u32,
                h: params.h as u32,
                _pad0: 0,
                _pad1: 0,
            }),
        );

        let render_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("render_bgl"),
            entries: &[bgl_uniform_frag(0), bgl_storage_frag(1)],
        });
        let render_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("render_pl"),
            bind_group_layouts: &[&render_bgl],
            push_constant_ranges: &[],
        });
        let render_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("render"),
            layout: Some(&render_layout),
            vertex: wgpu::VertexState {
                module: &rshader,
                entry_point: "vs_main",
                buffers: &[],
            },
            fragment: Some(wgpu::FragmentState {
                module: &rshader,
                entry_point: "fs_main",
                targets: &[Some(wgpu::ColorTargetState {
                    format: wgpu::TextureFormat::Rgba8Unorm,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
        });
        let render_target = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("render_target"),
            size: wgpu::Extent3d { width: params.w as u32, height: params.h as u32, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let render_bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("render_bg"),
            layout: &render_bgl,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: render_params_uniform.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: occ_buf.as_entire_binding() },
            ],
        });

        // Snapshot initial energy against the same accumulator the GPU uses,
        // so `energy()` at t=0 matches what running-delta bookkeeping would.
        let init_energy = total_energy_from_offsets(
            &occ,
            &u_edge,
            &off,
            params.h,
            params.w,
            params.periodic,
            params.charge,
        );

        let particles = occ.iter().filter(|&&b| b).count();

        Ok(Self {
            rng: Pcg64Mcg::seed_from_u64(params.seed),
            params,
            device,
            queue,
            adapter_info,
            occ_buf,
            blocked_buf,
            u_edge_buf,
            off_dy_buf,
            off_dx_buf,
            off_v_buf,
            tile_y_buf,
            tile_x_buf,
            params_uniform,
            accepted_buf,
            attempted_buf,
            energy_delta_buf,
            readback_acc,
            readback_att,
            readback_de,
            readback_occ,
            render_params_uniform,
            render_pipeline,
            render_bind_group,
            render_target,
            compute_pipeline,
            compute_bgl,
            noff,
            max_tiles: ntiles_max,
            tile_edge,
            rc,
            energy: init_energy,
            accepted: 0,
            attempted: 0,
            iteration: 0,
            peak_bytes,
            particles,
        })
    }

    pub fn adapter_info(&self) -> &AdapterInfo {
        &self.adapter_info
    }

    pub fn peak_gpu_bytes(&self) -> u64 {
        self.peak_bytes
    }

    pub fn tile_edge(&self) -> u32 {
        self.tile_edge
    }

    /// Advance the sim by roughly `target_attempts` Metropolis attempts. The
    /// GPU dispatch unit is a colour-sweep (4 dispatches, one per 2x2 colour),
    /// and each dispatch covers ntiles * moves_per_tile attempts, so the loop
    /// runs whole sweeps until the running count catches up. Returns the
    /// actual attempted-move count from GPU counters.
    pub fn step(&mut self, target_attempts: u64) -> u64 {
        let moves_per_tile: u32 = 64;
        let target = target_attempts.max(1);
        let mut done: u64 = 0;
        while done < target {
            done += self.sweep(self.params.temperature, moves_per_tile);
        }
        done
    }

    /// One four-colour sweep at `temperature`, with `moves_per_tile`
    /// sequential moves inside each tile. Returns attempted-move count.
    pub fn sweep(&mut self, temperature: f64, moves_per_tile: u32) -> u64 {
        let beta = 1.0 / (KB_EV * temperature.max(1e-9));
        let l = self.tile_edge;
        // Random offset shifts the tile grid each sweep - restores ergodicity
        // across tile seams, same argument as the reference kernel.
        let oy = self.rng.gen_range(0..l as i32);
        let ox = self.rng.gen_range(0..l as i32);
        let mut total_attempted: u64 = 0;
        for colour in 0..4u32 {
            let (tys, txs) = self.tiles_for_colour(colour, oy, ox);
            if tys.is_empty() {
                continue;
            }
            let n = tys.len() as u32;
            self.queue.write_buffer(&self.tile_y_buf, 0, bytemuck::cast_slice(&tys));
            self.queue.write_buffer(&self.tile_x_buf, 0, bytemuck::cast_slice(&txs));
            // Zero counters at the range we will overwrite.
            let bytes = (n as u64) * 4;
            self.queue.write_buffer(&self.accepted_buf, 0, &vec![0u8; bytes as usize]);
            self.queue.write_buffer(&self.attempted_buf, 0, &vec![0u8; bytes as usize]);
            self.queue.write_buffer(&self.energy_delta_buf, 0, &vec![0u8; bytes as usize]);

            let seed = self.rng.gen::<u32>().max(1);
            let gp = GpuParams {
                h: self.params.h as u32,
                w: self.params.w as u32,
                l_edge: self.tile_edge,
                rc: self.rc,
                hop: self.params.step_size as u32,
                ntiles: n,
                noff: self.noff,
                moves: moves_per_tile,
                periodic: if self.params.periodic { 1 } else { 0 },
                seed,
                q: self.params.charge as f32,
                beta: beta as f32,
            };
            self.queue.write_buffer(&self.params_uniform, 0, bytemuck::bytes_of(&gp));

            let bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("compute_bg"),
                layout: &self.compute_bgl,
                entries: &[
                    wgpu::BindGroupEntry { binding: 0, resource: self.params_uniform.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 1, resource: self.occ_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 2, resource: self.blocked_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 3, resource: self.u_edge_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 4, resource: self.tile_y_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 5, resource: self.tile_x_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 6, resource: self.off_dy_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 7, resource: self.off_dx_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 8, resource: self.off_v_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 9, resource: self.accepted_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 10, resource: self.attempted_buf.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 11, resource: self.energy_delta_buf.as_entire_binding() },
                ],
            });

            let mut enc = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("sweep_enc") });
            {
                let mut cp = enc.begin_compute_pass(&wgpu::ComputePassDescriptor { label: Some("cp"), timestamp_writes: None });
                cp.set_pipeline(&self.compute_pipeline);
                cp.set_bind_group(0, &bind_group, &[]);
                cp.dispatch_workgroups(n, 1, 1);
            }
            enc.copy_buffer_to_buffer(&self.accepted_buf, 0, &self.readback_acc, 0, bytes);
            enc.copy_buffer_to_buffer(&self.attempted_buf, 0, &self.readback_att, 0, bytes);
            enc.copy_buffer_to_buffer(&self.energy_delta_buf, 0, &self.readback_de, 0, bytes);
            self.queue.submit(Some(enc.finish()));

            let (acc, att, de) = self.map_counters(n as usize, bytes);
            self.accepted += acc;
            self.attempted += att;
            self.energy += de;
            total_attempted += att;
        }
        self.iteration += 1;
        total_attempted
    }

    fn map_counters(&self, n: usize, _bytes: u64) -> (u64, u64, f64) {
        let sacc = self.readback_acc.slice(0..(n as u64) * 4);
        let satt = self.readback_att.slice(0..(n as u64) * 4);
        let sde = self.readback_de.slice(0..(n as u64) * 4);
        let (t1, r1) = std::sync::mpsc::channel();
        sacc.map_async(wgpu::MapMode::Read, move |v| { let _ = t1.send(v); });
        let (t2, r2) = std::sync::mpsc::channel();
        satt.map_async(wgpu::MapMode::Read, move |v| { let _ = t2.send(v); });
        let (t3, r3) = std::sync::mpsc::channel();
        sde.map_async(wgpu::MapMode::Read, move |v| { let _ = t3.send(v); });
        self.device.poll(wgpu::Maintain::Wait);
        r1.recv().unwrap().unwrap();
        r2.recv().unwrap().unwrap();
        r3.recv().unwrap().unwrap();
        let acc_vec: Vec<u32> = bytemuck::cast_slice(&sacc.get_mapped_range()[..]).to_vec();
        let att_vec: Vec<u32> = bytemuck::cast_slice(&satt.get_mapped_range()[..]).to_vec();
        let de_vec: Vec<f32> = bytemuck::cast_slice(&sde.get_mapped_range()[..]).to_vec();
        // slice released implicitly


        self.readback_acc.unmap();
        self.readback_att.unmap();
        self.readback_de.unmap();
        let acc: u64 = acc_vec.iter().map(|&v| v as u64).sum();
        let att: u64 = att_vec.iter().map(|&v| v as u64).sum();
        let de: f64 = de_vec.iter().map(|&v| v as f64).sum();
        (acc, att, de)
    }

    fn tiles_for_colour(&self, colour: u32, oy: i32, ox: i32) -> (Vec<i32>, Vec<i32>) {
        let l = self.tile_edge as i32;
        let cy = (colour & 1) as i32;
        let cx = (colour >> 1) as i32;
        let mut ys: Vec<i32> = Vec::new();
        let mut xs: Vec<i32> = Vec::new();
        let h = self.params.h as i32;
        let w = self.params.w as i32;
        if self.params.periodic {
            let ny = (h / (2 * l)).max(0);
            let nx = (w / (2 * l)).max(0);
            for iy in 0..ny {
                ys.push(((oy + cy * l + 2 * l * iy) % h + h) % h);
            }
            for ix in 0..nx {
                xs.push(((ox + cx * l + 2 * l * ix) % w + w) % w);
            }
        } else {
            let mut y = -l + oy + cy * l;
            while y < h {
                if y + l > 0 && y < h {
                    ys.push(y);
                }
                y += 2 * l;
            }
            let mut x = -l + ox + cx * l;
            while x < w {
                if x + l > 0 && x < w {
                    xs.push(x);
                }
                x += 2 * l;
            }
        }
        let mut gy = Vec::with_capacity(ys.len() * xs.len());
        let mut gx = Vec::with_capacity(ys.len() * xs.len());
        for &y in &ys {
            for &x in &xs {
                gy.push(y);
                gx.push(x);
            }
        }
        // Guard against buffer overflow if the tile count exceeds the reserved
        // upper bound (should not happen, but a runtime check beats a silent
        // corrupted launch).
        if gy.len() as u32 > self.max_tiles {
            let n = self.max_tiles as usize;
            gy.truncate(n);
            gx.truncate(n);
        }
        (gy, gx)
    }

    pub fn occupancy(&self) -> Vec<bool> {
        let bytes = (self.params.h * self.params.w * 4) as u64;
        let mut enc = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("occ_copy") });
        enc.copy_buffer_to_buffer(&self.occ_buf, 0, &self.readback_occ, 0, bytes);
        self.queue.submit(Some(enc.finish()));
        let s = self.readback_occ.slice(0..bytes);
        let (t, r) = std::sync::mpsc::channel();
        s.map_async(wgpu::MapMode::Read, move |v| { let _ = t.send(v); });
        self.device.poll(wgpu::Maintain::Wait);
        r.recv().unwrap().unwrap();
        let v: Vec<u32> = bytemuck::cast_slice(&s.get_mapped_range()[..]).to_vec();
        // slice released implicitly
        self.readback_occ.unmap();
        v.into_iter().map(|x| x != 0).collect()
    }

    pub fn energy(&self) -> f64 {
        self.energy
    }

    pub fn stats(&self) -> Stats {
        Stats {
            iteration: self.iteration,
            proposed: self.attempted,
            accepted: self.accepted,
            dead_proposals: 0,
            fail_streak: 0,
            batch: self.tile_edge as usize,
            particles: self.particles,
            energy: self.energy,
            temperature: self.params.temperature,
        }
    }

    pub fn set_temperature(&mut self, t: f64) {
        self.params.temperature = t;
    }

    /// Recompute total energy from the current GPU occupancy against the same
    /// offset table the kernel uses. Slow (CPU O(N * noff)); use to check that
    /// the running delta has not drifted, or at scenario end.
    pub fn recompute_energy(&mut self) -> f64 {
        let occ = self.occupancy();
        let off = build_offset_table(&self.params);
        // u_edge is CPU-side static in M2 (no brush strokes on the GPU path yet).
        let ue = self.read_u_edge();
        let e = total_energy_from_offsets(
            &occ,
            &ue,
            &off,
            self.params.h,
            self.params.w,
            self.params.periodic,
            self.params.charge,
        );
        self.energy = e;
        self.particles = occ.iter().filter(|&&b| b).count();
        e
    }

    fn read_u_edge(&self) -> Vec<f32> {
        let bytes = (self.params.h * self.params.w * 4) as u64;
        let readback = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback_u_edge"),
            size: bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let mut enc = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("u_edge_copy") });
        enc.copy_buffer_to_buffer(&self.u_edge_buf, 0, &readback, 0, bytes);
        self.queue.submit(Some(enc.finish()));
        let s = readback.slice(0..bytes);
        let (t, r) = std::sync::mpsc::channel();
        s.map_async(wgpu::MapMode::Read, move |v| { let _ = t.send(v); });
        self.device.poll(wgpu::Maintain::Wait);
        r.recv().unwrap().unwrap();
        let out: Vec<f32> = bytemuck::cast_slice(&s.get_mapped_range()[..]).to_vec();
        // slice released implicitly
        out
    }

    /// Run one render pass into the offscreen target and submit. Returns
    /// immediately (fire-and-forget for bench throughput measurement).
    pub fn render_frame(&self) {
        let view = self.render_target.create_view(&wgpu::TextureViewDescriptor::default());
        let mut enc = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("render_enc") });
        {
            let mut rp = enc.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("rp"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            rp.set_pipeline(&self.render_pipeline);
            rp.set_bind_group(0, &self.render_bind_group, &[]);
            rp.draw(0..3, 0..1);
        }
        self.queue.submit(Some(enc.finish()));
    }

    pub fn poll(&self) {
        self.device.poll(wgpu::Maintain::Wait);
    }

    pub fn params(&self) -> &Params {
        &self.params
    }
}

fn bgl_uniform(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Uniform,
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

fn bgl_storage(binding: u32, read_only: bool) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Storage { read_only },
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

fn bgl_uniform_frag(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Uniform,
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

fn bgl_storage_frag(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::FRAGMENT,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Storage { read_only: true },
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn instances_and_conserves_particles() {
        let mut params = Params::default();
        params.h = 32;
        params.w = 32;
        params.cutoff = 6.0;
        params.temperature = 50_000.0;
        params.seed = 1;
        params.step_size = 2;
        let mut sim = match GpuSim::new_blank(params.clone(), 80) {
            Ok(s) => s,
            Err(GpuError::AdapterNotFound) => {
                eprintln!("skip: no adapter");
                return;
            }
            Err(e) => panic!("gpu init failed: {}", e),
        };
        let n0 = sim.occupancy().iter().filter(|&&b| b).count();
        for _ in 0..8 {
            sim.sweep(params.temperature, 32);
        }
        let n1 = sim.occupancy().iter().filter(|&&b| b).count();
        assert_eq!(n0, n1, "particle count changed on GPU: {} -> {}", n0, n1);
    }
}
