#!/bin/bash
set -euo pipefail
export WORKSPACE="${PWD}"
export RUSTFLAGS="-C target-feature=+crt-static"
export ICEDTEAWEB_INSTALL="$(cygpath -u "${WORKSPACE}/icedtea-web-image")"
export WIXPATH="$(cygpath -u "C:/PROGRA~2/WIXTOO~1.14/bin")"
export WIXGEN="$(cygpath -u "C:/cygwin64/usr/share/java/wixgen.jar")"
export PACK_JAR="$(cygpath -u "C:/cygwin64/usr/share/java/pack.jar")"
export BYTEBUDDY_JAR="$(cygpath -u "C:/cygwin64/usr/share/java/byte-buddy.jar")"
export BYTEBUDDY_AGENT_JAR="$(cygpath -u "C:/cygwin64/usr/share/java/byte-buddy-agent.jar")"
# Prefer rustup cargo bin; keep legacy C:\rust\bin (junction created by install-toolchain.ps1).
CARGO_BIN=""
if [ -n "${USERPROFILE:-}" ] && [ -d "${USERPROFILE}/.cargo/bin" ]; then
	CARGO_BIN="$(cygpath -u "${USERPROFILE}/.cargo/bin")"
elif [ -n "${HOME:-}" ] && [ -d "${HOME}/.cargo/bin" ]; then
	CARGO_BIN="$(cygpath -u "${HOME}/.cargo/bin")"
fi
export PATH="${PATH}:${CARGO_BIN}:/cygdrive/c/rust/bin:${WIXPATH}"
if ! command -v cargo >/dev/null 2>&1; then
	echo "ERROR: cargo not found on PATH (expected rustup under ~/.cargo/bin or C:\\rust\\bin)." >&2
	echo "PATH=${PATH}" >&2
	exit 1
fi
echo "Using $(command -v cargo) ($(cargo --version))"
echo "Using $(command -v rustc) ($(rustc --version))"
rustc -Vv | sed -n 's/^host: /rustc host: /p' || true
if [ -n "${RUSTUP_TOOLCHAIN:-}" ]; then
	echo "RUSTUP_TOOLCHAIN=${RUSTUP_TOOLCHAIN}"
fi
export JVM_HOME_SHORT="$(cygpath -d "${JAVA_HOME}")"
export JVMPATH="$(cygpath -u ${JVM_HOME_SHORT})"
echo "Configure IcedTea-Web"
./autogen.sh
./configure --disable-native-plugin --prefix="${ICEDTEAWEB_INSTALL}" --with-wix=${WIXPATH} --with-wixgen=${WIXGEN} --with-itw-libs=BUNDLED --with-pack="${PACK_JAR}" --with-jdk-home="${JVMPATH}" --with-bytebuddy="${BYTEBUDDY_JAR}" --with-bytebuddy-agent="${BYTEBUDDY_AGENT_JAR}"
# rustc 1.85 cannot install latest cargo-audit (needs 1.88+); pin a compatible release.
if [ -f Makefile ]; then
	sed -i 's|$(CARGO) install cargo-audit ;|$(CARGO) install cargo-audit --version 0.22.1 --locked ;|g' Makefile
fi
echo "Build IcedTea-Web"
make
echo "Create IcedTea-Web Distribution"
make win-bin-dist
find . -name "icedtea-web-*.win.bin.zip"
echo "Create IcedTea-Web MSI"
make win-installer
find . -name "icedtea-web-*.msi"
echo "Generate shasums"
shopt -s nullglob
for zip in icedtea-web-*.win.bin.zip; do
	shasum -a 256 "$zip" > "$zip.sha256.txt"
done
for msi in **/icedtea-web-*.msi; do
	shasum -a 256 "$msi" > "$msi.sha256.txt"
done