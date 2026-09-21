// M3b: surface-attached wgpu renderer.
//
// The Kotlin side hands a `Surface` (from Compose's AndroidExternalSurface or
// a SurfaceView holder) down through JNI. `ANativeWindow_fromSurface` turns
// that into a raw `ANativeWindow*` we can plug into wgpu's `SurfaceTargetUnsafe`
// with a `raw_window_handle::AndroidNdkWindowHandle`.
//
// The renderer owns:
//   - a wgpu Instance / Adapter / Device / Queue,
//   - the swapchain-shaped Surface,
//   - a storage buffer that holds the CPU-side occupancy for the render pass,
//   - the same render pipeline `coulomb-gpu` uses, wired to sample the storage
//     buffer against the same fullscreen triangle.
//
// The compute-on-device physics port is a separate change (see the plan doc):
// this M3b renderer samples the CPU-run sim's occupancy buffer, so the paint
// path is proven, the wgpu surface plumbing is proven, and swapping physics
// engines later is a single-file change on the caller.
//
// Non-android builds compile the module as a stub. `cargo test -p coulomb-jni`
// on the workstation host still exercises the CPU-side plumbing that the JNI
// entry points are wrappers over; a real GPU is required to exercise the
// surface path meaningfully, and that lives on the S24 by design.

#[cfg(target_os = "android")]
pub use android::Renderer;
#[cfg(not(target_os = "android"))]
pub use stub::Renderer;

/// Shared metadata about the adapter chosen for the surface. Reported through
/// `nativeSimAdapterInfo` so the diagnostic overlay can show which GPU wgpu
/// picked - a mislabelled "Adreno 750 physics on Mali" would be worse than a
/// missing number, so this string comes straight from the driver.
#[derive(Debug, Clone, Default)]
pub struct AdapterInfo {
    pub name: String,
    pub backend: String,
    pub driver: String,
    pub device_type: String,
}

#[cfg(not(target_os = "android"))]
mod stub {
    use super::AdapterInfo;
    use jni::sys::jobject;
    use jni::JNIEnv;

    // The host build has no ANativeWindow_fromSurface, and the tests do not
    // create a surface. The stub exists so the JNI file still compiles and
    // host tests still link. `new` refuses to construct one so a Kotlin caller
    // running against a host build behaves as if no surface bind succeeded.
    pub struct Renderer(());

    impl Renderer {
        /// SAFETY: The Android-target signature is unsafe because a real
        /// bind dereferences the Surface; the host stub takes the same
        /// signature so lib.rs need not `cfg`-guard the call site.
        pub unsafe fn new(_env: &JNIEnv, _surface: jobject) -> Result<Self, String> {
            Err("surface renderer unavailable on host build".into())
        }

        pub fn info(&self) -> AdapterInfo {
            AdapterInfo::default()
        }

        pub fn resize(&mut self, _w: u32, _h: u32) {}

        pub fn render(&mut self, _occ: &[u32], _lattice_w: u32, _lattice_h: u32) {}
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::AdapterInfo;
    use bytemuck::{Pod, Zeroable};
    use jni::sys::jobject;
    use jni::JNIEnv;
    use raw_window_handle::{
        AndroidDisplayHandle, AndroidNdkWindowHandle, DisplayHandle, HandleError, HasDisplayHandle,
        HasWindowHandle, RawDisplayHandle, RawWindowHandle, WindowHandle,
    };
    use std::borrow::Cow;
    use std::ffi::c_void;
    use std::ptr::NonNull;
    use wgpu::util::DeviceExt;

    // Fragment-side uniform paired with the fullscreen triangle. Kept in one
    // place so the Rust struct and WGSL declaration cannot silently drift; a
    // mismatched field silently miscolours pixels rather than crashing, and
    // that class of bug is expensive to diagnose on device.
    #[repr(C)]
    #[derive(Clone, Copy, Pod, Zeroable)]
    struct RenderParams {
        w: u32,
        h: u32,
        _pad0: u32,
        _pad1: u32,
    }

    // Vulkan's dynamic-loading crash on a released ANativeWindow surface is
    // spectacular; keep the pointer and release it on drop, so a lifecycle bug
    // in Kotlin surfaces as a wgpu error, not a native SIGSEGV. `ANativeWindow`
    // is refcounted; `_fromSurface` increments and `_release` decrements.
    struct NativeWindowGuard {
        raw: NonNull<ndk_sys::ANativeWindow>,
    }

    impl NativeWindowGuard {
        // SAFETY: env must be a live JNIEnv and surface must be a jobject
        // that Android's `Surface` class instance; the two together satisfy
        // ANativeWindow_fromSurface's contract. On a null/wrong-type argument
        // the function returns null, which we forward as `Err`.
        unsafe fn from_surface(env: &JNIEnv, surface: jobject) -> Result<Self, String> {
            if surface.is_null() {
                return Err("bind_surface: surface object is null".into());
            }
            // ndk_sys re-exports jni_sys types via a private alias, so we
            // reach through to `jni_sys::JNIEnv` (the same underlying type)
            // instead. `env.get_raw()` is `*mut jni_sys::JNIEnv`, so the
            // cast is a straight pointer forward.
            let raw_env = env.get_raw();
            let win = unsafe { ndk_sys::ANativeWindow_fromSurface(raw_env as *mut _, surface as *mut _) };
            let ptr = NonNull::new(win).ok_or_else(|| {
                "bind_surface: ANativeWindow_fromSurface returned null".to_string()
            })?;
            Ok(NativeWindowGuard { raw: ptr })
        }

        fn as_ptr(&self) -> *mut c_void {
            self.raw.as_ptr() as *mut c_void
        }

        fn width(&self) -> i32 {
            // SAFETY: pointer is live until Drop.
            unsafe { ndk_sys::ANativeWindow_getWidth(self.raw.as_ptr()) }
        }

        fn height(&self) -> i32 {
            // SAFETY: pointer is live until Drop.
            unsafe { ndk_sys::ANativeWindow_getHeight(self.raw.as_ptr()) }
        }
    }

    impl Drop for NativeWindowGuard {
        fn drop(&mut self) {
            // SAFETY: paired with the from_surface acquire above.
            unsafe { ndk_sys::ANativeWindow_release(self.raw.as_ptr()) };
        }
    }

    // Thin adapter that turns the ANativeWindow into what wgpu's
    // `create_surface_unsafe` wants. wgpu requires a `HasWindowHandle` +
    // `HasDisplayHandle` implementor; the pointer lifetime is bounded by the
    // Renderer that owns the guard, which is checked at drop.
    struct AndroidWindow<'a> {
        window: &'a NativeWindowGuard,
    }

    impl<'a> HasWindowHandle for AndroidWindow<'a> {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            let h = AndroidNdkWindowHandle::new(
                NonNull::new(self.window.as_ptr()).expect("non-null ANativeWindow"),
            );
            let raw = RawWindowHandle::AndroidNdk(h);
            // SAFETY: the AndroidWindow does not outlive `self.window`, which
            // holds the ANativeWindow reference count.
            Ok(unsafe { WindowHandle::borrow_raw(raw) })
        }
    }

    impl<'a> HasDisplayHandle for AndroidWindow<'a> {
        fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
            let raw = RawDisplayHandle::Android(AndroidDisplayHandle::new());
            // SAFETY: Android's display handle carries no lifetime.
            Ok(unsafe { DisplayHandle::borrow_raw(raw) })
        }
    }

    pub struct Renderer {
        // Window guard must outlive `surface`, which is the reason `surface`
        // is dropped first in Drop below.
        _window: NativeWindowGuard,
        surface: wgpu::Surface<'static>,
        device: wgpu::Device,
        queue: wgpu::Queue,
        info: AdapterInfo,

        surface_config: wgpu::SurfaceConfiguration,
        pipeline: wgpu::RenderPipeline,
        bind_group_layout: wgpu::BindGroupLayout,
        params_uniform: wgpu::Buffer,
        occ_buf: wgpu::Buffer,
        occ_capacity: usize,
        occ_lattice_w: u32,
        occ_lattice_h: u32,
        bind_group: wgpu::BindGroup,
    }

    // The wgpu Surface is `Surface<'static>` because we hand it a heap-owned
    // ANativeWindow reference; `create_surface_unsafe` returns a static-lifetime
    // Surface when the raw handle lives at least as long as itself. Our guard
    // sits inside the Renderer, so this holds until Drop.
    impl Renderer {
        // SAFETY: `env` and `surface` come from a JNI entry point that is
        // called by Kotlin with a live Surface. The rest of construction is
        // safe wgpu.
        pub unsafe fn new(env: &JNIEnv, surface: jobject) -> Result<Self, String> {
            // SAFETY: contract on this function's caller.
            let guard = unsafe { NativeWindowGuard::from_surface(env, surface) }?;
            let width = guard.width().max(1) as u32;
            let height = guard.height().max(1) as u32;

            let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
                backends: wgpu::Backends::VULKAN | wgpu::Backends::GL,
                ..Default::default()
            });

            // Build a wgpu::Surface against the ANativeWindow. The `unsafe`
            // is inside `create_surface_unsafe`, which we call with a target
            // that borrows only the guard - guard lives inside Renderer.
            let window_adapter = AndroidWindow { window: &guard };
            // SAFETY: the window handle we serve stays valid until this
            // Renderer's Drop releases the NativeWindowGuard.
            let target = unsafe { wgpu::SurfaceTargetUnsafe::from_window(&window_adapter) }
                .map_err(|e| format!("bind_surface: build surface target: {e}"))?;
            // SAFETY: window handle points into the guard we own; we drop the
            // surface before the guard in Drop below. wgpu 0.19's
            // `create_surface_unsafe` returns `Surface<'static>` unconditionally,
            // so no lifetime transmute is needed - the ownership contract is
            // ours to enforce via the guard.
            let surface = unsafe { instance.create_surface_unsafe(target) }
                .map_err(|e| format!("bind_surface: create wgpu surface: {e}"))?;

            let adapter = pollster::block_on(instance.request_adapter(
                &wgpu::RequestAdapterOptions {
                    power_preference: wgpu::PowerPreference::HighPerformance,
                    force_fallback_adapter: false,
                    compatible_surface: Some(&surface),
                },
            ))
            .ok_or_else(|| "bind_surface: no compatible adapter".to_string())?;

            let ainfo = adapter.get_info();
            let info = AdapterInfo {
                name: ainfo.name.clone(),
                backend: format!("{:?}", ainfo.backend),
                driver: ainfo.driver.clone(),
                device_type: format!("{:?}", ainfo.device_type),
            };

            let (device, queue) = pollster::block_on(adapter.request_device(
                &wgpu::DeviceDescriptor {
                    label: Some("coulomb-jni renderer"),
                    required_features: wgpu::Features::empty(),
                    required_limits: adapter.limits(),
                },
                None,
            ))
            .map_err(|e| format!("bind_surface: request_device: {e}"))?;

            let caps = surface.get_capabilities(&adapter);
            let format = caps
                .formats
                .iter()
                .copied()
                .find(|f| matches!(f, wgpu::TextureFormat::Rgba8Unorm | wgpu::TextureFormat::Bgra8Unorm))
                .unwrap_or(caps.formats[0]);
            let present_mode = if caps.present_modes.contains(&wgpu::PresentMode::Mailbox) {
                wgpu::PresentMode::Mailbox
            } else {
                wgpu::PresentMode::Fifo
            };
            let alpha_mode = caps
                .alpha_modes
                .iter()
                .copied()
                .find(|a| matches!(a, wgpu::CompositeAlphaMode::Opaque | wgpu::CompositeAlphaMode::PostMultiplied))
                .unwrap_or(caps.alpha_modes[0]);
            let surface_config = wgpu::SurfaceConfiguration {
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
                format,
                width,
                height,
                present_mode,
                alpha_mode,
                view_formats: vec![],
                desired_maximum_frame_latency: 2,
            };
            surface.configure(&device, &surface_config);

            let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("surface_render.wgsl"),
                source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(SURFACE_RENDER_WGSL)),
            });

            let bind_group_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("surface_render_bgl"),
                entries: &[
                    wgpu::BindGroupLayoutEntry {
                        binding: 0,
                        visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Uniform,
                            has_dynamic_offset: false,
                            min_binding_size: None,
                        },
                        count: None,
                    },
                    wgpu::BindGroupLayoutEntry {
                        binding: 1,
                        visibility: wgpu::ShaderStages::FRAGMENT,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Storage { read_only: true },
                            has_dynamic_offset: false,
                            min_binding_size: None,
                        },
                        count: None,
                    },
                ],
            });
            let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("surface_render_pl"),
                bind_group_layouts: &[&bind_group_layout],
                push_constant_ranges: &[],
            });
            let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("surface_render"),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState { module: &shader, entry_point: "vs_main", buffers: &[] },
                fragment: Some(wgpu::FragmentState {
                    module: &shader,
                    entry_point: "fs_main",
                    targets: &[Some(wgpu::ColorTargetState {
                        format,
                        blend: None,
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview: None,
            });

            let params_uniform = device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("surface_render_params"),
                size: std::mem::size_of::<RenderParams>() as u64,
                usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });

            // Initial occupancy buffer is a placeholder; the first render pass
            // resizes it to match the actual lattice size. Keeping a 1-element
            // buffer here lets the bind group be built up-front so the render
            // path stays branch-free once the first frame lands.
            let placeholder: [u32; 1] = [0];
            let occ_buf = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("surface_render_occ"),
                contents: bytemuck::cast_slice(&placeholder),
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            });
            let bind_group = make_bind_group(
                &device,
                &bind_group_layout,
                &params_uniform,
                &occ_buf,
            );

            Ok(Renderer {
                _window: guard,
                surface,
                device,
                queue,
                info,
                surface_config,
                pipeline,
                bind_group_layout,
                params_uniform,
                occ_buf,
                occ_capacity: 1,
                occ_lattice_w: 1,
                occ_lattice_h: 1,
                bind_group,
            })
        }

        pub fn info(&self) -> AdapterInfo {
            self.info.clone()
        }

        pub fn resize(&mut self, w: u32, h: u32) {
            if w == 0 || h == 0 {
                return;
            }
            if self.surface_config.width == w && self.surface_config.height == h {
                return;
            }
            self.surface_config.width = w;
            self.surface_config.height = h;
            self.surface.configure(&self.device, &self.surface_config);
        }

        pub fn render(&mut self, occ: &[u32], lattice_w: u32, lattice_h: u32) {
            // Grow the GPU occupancy buffer if the CPU sim's lattice grew or
            // the caller resized. Buffer capacity is tracked in u32 elements.
            let needed = lattice_w as usize * lattice_h as usize;
            if needed == 0 {
                return;
            }
            if needed > self.occ_capacity {
                self.occ_buf = self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("surface_render_occ"),
                    size: (needed * 4) as u64,
                    usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                });
                self.occ_capacity = needed;
                self.bind_group = make_bind_group(
                    &self.device,
                    &self.bind_group_layout,
                    &self.params_uniform,
                    &self.occ_buf,
                );
            }
            if lattice_w != self.occ_lattice_w || lattice_h != self.occ_lattice_h {
                self.occ_lattice_w = lattice_w;
                self.occ_lattice_h = lattice_h;
                self.queue.write_buffer(
                    &self.params_uniform,
                    0,
                    bytemuck::bytes_of(&RenderParams {
                        w: lattice_w,
                        h: lattice_h,
                        _pad0: 0,
                        _pad1: 0,
                    }),
                );
            }
            // The occ slice comes from the CPU sim and is authoritative; on a
            // 512x512 lattice the upload is 1 MiB, which is quick over the
            // Vulkan-shared memory on Adreno 750. We copy only the used prefix
            // to avoid a spurious wide write when the caller's buffer is short.
            let byte_len = needed * 4;
            self.queue
                .write_buffer(&self.occ_buf, 0, &bytemuck::cast_slice(occ)[..byte_len]);

            let frame = match self.surface.get_current_texture() {
                Ok(f) => f,
                Err(wgpu::SurfaceError::Outdated) | Err(wgpu::SurfaceError::Lost) => {
                    // A layout / rotation change reconfigures the swapchain.
                    // The next call to `render` will pick up the new frame.
                    self.surface.configure(&self.device, &self.surface_config);
                    return;
                }
                Err(_) => return,
            };
            let view = frame
                .texture
                .create_view(&wgpu::TextureViewDescriptor::default());
            let mut enc = self
                .device
                .create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("surface_render_enc") });
            {
                let mut rp = enc.begin_render_pass(&wgpu::RenderPassDescriptor {
                    label: Some("surface_render_rp"),
                    color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                        view: &view,
                        resolve_target: None,
                        ops: wgpu::Operations {
                            load: wgpu::LoadOp::Clear(wgpu::Color { r: 0.021, g: 0.028, b: 0.043, a: 1.0 }),
                            store: wgpu::StoreOp::Store,
                        },
                    })],
                    depth_stencil_attachment: None,
                    timestamp_writes: None,
                    occlusion_query_set: None,
                });
                rp.set_pipeline(&self.pipeline);
                rp.set_bind_group(0, &self.bind_group, &[]);
                rp.draw(0..3, 0..1);
            }
            self.queue.submit(Some(enc.finish()));
            frame.present();
        }
    }

    fn make_bind_group(
        device: &wgpu::Device,
        layout: &wgpu::BindGroupLayout,
        params: &wgpu::Buffer,
        occ: &wgpu::Buffer,
    ) -> wgpu::BindGroup {
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("surface_render_bg"),
            layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: params.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: occ.as_entire_binding() },
            ],
        })
    }

    // Two-triangle full-viewport shader that samples the CPU occupancy buffer
    // and paints each occupied cell in the mockup's warm accent, unoccupied
    // cells in the mockup's canvas indigo. Kept inline rather than referring
    // to coulomb-gpu's render.wgsl because the surface path uses a portrait
    // aspect the desktop render does not.
    const SURFACE_RENDER_WGSL: &str = r#"
struct Params {
    w: u32,
    h: u32,
};

@group(0) @binding(0) var<uniform> P: Params;
@group(0) @binding(1) var<storage, read> occ: array<u32>;

struct VsOut {
    @builtin(position) pos: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vid: u32) -> VsOut {
    var xy = array<vec2<f32>, 3>(
        vec2<f32>(-1.0, -1.0),
        vec2<f32>( 3.0, -1.0),
        vec2<f32>(-1.0,  3.0),
    );
    var out: VsOut;
    out.pos = vec4<f32>(xy[vid], 0.0, 1.0);
    out.uv = 0.5 * (xy[vid] + vec2<f32>(1.0, 1.0));
    return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    let sx = clamp(u32(in.uv.x * f32(P.w)), 0u, P.w - 1u);
    let sy = clamp(u32((1.0 - in.uv.y) * f32(P.h)), 0u, P.h - 1u);
    let v = occ[sy * P.w + sx];
    if v == 0u {
        return vec4<f32>(0.021, 0.028, 0.043, 1.0);
    }
    return vec4<f32>(0.98, 0.76, 0.28, 1.0);
}
"#;
}
