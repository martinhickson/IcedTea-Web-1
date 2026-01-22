fn main() {
    // Link against Windows system libraries
    // Check for Windows targets (both MSVC and GNU toolchains)
    let target = std::env::var("TARGET").unwrap_or_default();
    eprintln!("build.rs: TARGET = {}", target);
    
    if target.contains("windows") {
        eprintln!("build.rs: Detected Windows target, linking advapi32 and kernel32");
        // Registry API functions (RegOpenKeyExW, RegCloseKey, RegQueryValueExW, etc.)
        println!("cargo:rustc-link-lib=advapi32");
        // Windows API functions (AttachConsole, MultiByteToWideChar, WideCharToMultiByte, GetLastError, FormatMessageW, LocalFree)
        // Note: kernel32 is usually linked automatically, but being explicit doesn't hurt
        println!("cargo:rustc-link-lib=kernel32");
    } else {
        eprintln!("build.rs: Non-Windows target, skipping Windows library linking");
    }
}

