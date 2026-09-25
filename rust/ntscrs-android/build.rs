/* SPDX-License-Identifier: GPL-2.0-or-later */
use std::env;

fn android_abi(target: &str) -> Option<&'static str> {
    Some(match target {
        "aarch64-linux-android" => "arm64-v8a",
        "armv7-linux-androideabi" => "armeabi-v7a",
        "x86_64-linux-android" => "x86_64",
        "i686-linux-android" => "x86",
        "x86_64-unknown-linux-gnu" => "host",
        _ => return None,
    })
}

fn main() {
    let target = env::var("TARGET").expect("TARGET not set");
    let abi = android_abi(&target).expect("unsupported target for x264/ffmpeg");
    let manifest_dir = env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR not set");
    let x264_dir = format!("{manifest_dir}/../x264/{abi}");
    let ff_dir = format!("{manifest_dir}/../ffmpeg/{abi}");

    // Compile the C shims against this ABI's x264 + ffmpeg headers.
    cc::Build::new()
        .file("src/x264enc_shim.c")
        .file("src/ffshim.c")
        .include(format!("{x264_dir}/include"))
        .include(format!("{ff_dir}/include"))
        .warnings(false)
        .compile("ntscrs_shim");

    // Link prebuilt static libs.
    println!("cargo:rustc-link-search=native={ff_dir}/lib");
    println!("cargo:rustc-link-search=native={x264_dir}/lib");
    println!("cargo:rustc-link-lib=static=avformat");
    println!("cargo:rustc-link-lib=static=avcodec");
    println!("cargo:rustc-link-lib=static=swscale");
    println!("cargo:rustc-link-lib=static=avutil");
    println!("cargo:rustc-link-lib=static=x264");
    println!("cargo:rustc-link-lib=z");
    println!("cargo:rustc-link-lib=m");
    println!("cargo:rustc-link-lib=dl");
    if target.contains("linux-gnu") && !target.contains("android") {
        println!("cargo:rustc-link-lib=pthread");
    }

    println!("cargo:rerun-if-changed=src/x264enc_shim.c");
    println!("cargo:rerun-if-changed=src/ffshim.c");
    println!("cargo:rerun-if-changed=build.rs");
}
