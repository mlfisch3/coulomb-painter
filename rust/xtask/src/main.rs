// Repo automation. Two entry points today:
//
//   cargo xtask validate-wgsl   - parse coulomb-gpu's WGSL, run the naga
//                                 validator, and emit SPIR-V so an Android
//                                 Vulkan backend accepting SPIR-V will accept
//                                 the shaders.
//   cargo xtask android-build   - the previous plus `cargo ndk` for both
//                                 android targets. Prints the built .so paths
//                                 and sizes.
//
// `build.rs` was rejected on purpose: running naga on every incremental
// compile burns cycles for zero incremental value, and this xtask is the one
// place we care about the check.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("validate-wgsl") => run(validate_wgsl()),
        Some("android-build") => run(android_build()),
        Some(other) => {
            eprintln!("xtask: unknown task {other:?}");
            usage();
            ExitCode::from(2)
        }
        None => {
            usage();
            ExitCode::from(2)
        }
    }
}

fn usage() {
    eprintln!("usage: cargo xtask <task>");
    eprintln!("tasks: validate-wgsl, android-build");
}

fn run(res: Result<(), String>) -> ExitCode {
    match res {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("xtask: {e}");
            ExitCode::FAILURE
        }
    }
}

fn workspace_root() -> PathBuf {
    // xtask is a workspace member at rust/xtask, and CARGO_MANIFEST_DIR points
    // at the crate. The workspace root (the `rust/` directory) is one level up.
    let manifest = env!("CARGO_MANIFEST_DIR");
    PathBuf::from(manifest).parent().expect("xtask has no parent dir").to_path_buf()
}

// WGSL entry points that must survive naga validation and SPIR-V lowering.
// Kept in one list here rather than scanned from disk so a new entry point
// added upstream trips this check explicitly.
const WGSL_TARGETS: &[(&str, &[&str])] = &[
    ("coulomb-gpu/src/shaders/metropolis.wgsl", &["tile_metropolis"]),
    ("coulomb-gpu/src/shaders/render.wgsl", &["vs_main", "fs_main"]),
];

fn validate_wgsl() -> Result<(), String> {
    use naga::back::spv::{Options as SpvOptions, WriterFlags};
    use naga::valid::{Capabilities, ValidationFlags, Validator};

    let root = workspace_root();
    for (rel, entries) in WGSL_TARGETS {
        let path = root.join(rel);
        eprintln!("== validating {}", path.display());
        let src = fs::read_to_string(&path)
            .map_err(|e| format!("read {}: {e}", path.display()))?;

        let module = naga::front::wgsl::parse_str(&src)
            .map_err(|e| format!("wgsl parse {}: {}", path.display(), e.emit_to_string(&src)))?;

        // ValidationFlags::all() is what wgpu uses internally, so this matches
        // the runtime's strictness rather than a subset.
        let info = Validator::new(ValidationFlags::all(), Capabilities::empty())
            .validate(&module)
            .map_err(|e| format!("wgsl validate {}: {e:?}", path.display()))?;

        for entry in module.entry_points.iter() {
            if !entries.contains(&entry.name.as_str()) {
                return Err(format!(
                    "{}: unexpected entry point {:?}; update WGSL_TARGETS",
                    path.display(),
                    entry.name
                ));
            }
        }
        for want in *entries {
            if !module.entry_points.iter().any(|e| e.name == *want) {
                return Err(format!(
                    "{}: missing expected entry point {want:?}",
                    path.display()
                ));
            }
        }

        // Emit SPIR-V. The Android Vulkan backend consumes SPIR-V, so a clean
        // lowering here is the concrete proof the WGSL is accepted, not just
        // that naga's front end parsed it.
        let mut spv_opts = SpvOptions::default();
        spv_opts.flags = WriterFlags::empty();
        let _ = naga::back::spv::write_vec(&module, &info, &spv_opts, None)
            .map_err(|e| format!("spv lower {}: {e}", path.display()))?;
    }
    Ok(())
}

fn android_build() -> Result<(), String> {
    let ndk = env::var("ANDROID_NDK_HOME")
        .or_else(|_| env::var("ANDROID_NDK_ROOT"))
        .map_err(|_| {
            "ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) is not set. \
             See rust/README.md 'Android build' for prerequisites."
                .to_string()
        })?;
    if !Path::new(&ndk).join("source.properties").exists() {
        return Err(format!(
            "ANDROID_NDK_HOME={ndk} does not look like an NDK install \
             (source.properties missing)"
        ));
    }
    eprintln!("== android NDK: {ndk}");

    validate_wgsl()?;

    let root = workspace_root();
    let status = Command::new("cargo")
        .current_dir(&root)
        .args([
            "ndk",
            "-t",
            "arm64-v8a",
            "-t",
            "armeabi-v7a",
            "build",
            "--release",
            "-p",
            "coulomb-core",
            "-p",
            "coulomb-gpu",
            "-p",
            "coulomb-jni",
        ])
        .status()
        .map_err(|e| format!("spawn cargo ndk: {e}. Is cargo-ndk installed?"))?;
    if !status.success() {
        return Err(format!("cargo ndk failed with {status}"));
    }

    let outputs = [
        ("aarch64-linux-android", "arm64-v8a"),
        ("armv7-linux-androideabi", "armeabi-v7a"),
    ];
    // libcoulomb_jni.so is the only .so actually loaded by System.loadLibrary
    // in the app: it re-exports what it uses from coulomb-core and coulomb-gpu
    // (both linked as rlibs into the cdylib). The bare core/gpu .so files are
    // kept in the report as an incidental sanity check that the whole crate
    // set still cross-compiles cleanly.
    let libs = ["libcoulomb_core.so", "libcoulomb_gpu.so", "libcoulomb_jni.so"];
    println!();
    println!("built shared libraries:");
    for (triple, abi) in outputs {
        for lib in libs {
            let p = root.join("target").join(triple).join("release").join(lib);
            let size = fs::metadata(&p)
                .map_err(|e| format!("stat {}: {e}", p.display()))?
                .len();
            println!("  [{abi:>10}] {:>9} bytes  {}", size, p.display());
        }
    }
    Ok(())
}
