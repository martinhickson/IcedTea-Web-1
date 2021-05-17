#!/bin/bash
export WORKSPACE="${PWD}"
export RUSTFLAGS="-C target-feature=+crt-static"
export ICEDTEAWEB_INSTALL="$(cygpath -u "${WORKSPACE}/icedtea-web-image")"
export WIXPATH="$(cygpath -u "C:/PROGRA~2/WIXTOO~1.14/bin")"
export WIXGEN="$(cygpath -u "C:/cygwin64/usr/share/java/wixgen.jar")"
export PATH="${PATH}:/cygdrive/c/rust/bin:${WIXPATH}"
export JVM_HOME_SHORT="$(cygpath -d "${JAVA_HOME}")"
export JVMPATH="$(cygpath -u ${JVM_HOME_SHORT})"
echo "Configure IcedTea-Web"
./autogen.sh
./configure --disable-native-plugin --prefix="${ICEDTEAWEB_INSTALL}" --with-wix=${WIXPATH} --with-wixgen=${WIXGEN} --with-itw-libs=BUNDLED --with-jdk-home="${JVMPATH}"
echo "Build IcedTea-Web"
make
echo "Create IcedTea-Web Distribution"
make win-bin-dist
find . -name "icedtea-web-*.win.bin.zip"
echo "Create IcedTea-Web MSI"
make win-installer
find . -name "icedtea-web-*.msi"
echo "Generate shasums"
for zip in icedtea-web-*.win.bin.zip; do
	shasum -a 256 "$zip" > "$zip.sha256.txt"
done
for msi in **/icedtea-web-*.msi; do
	shasum -a 256 "$msi" > "$msi.sha256.txt"
done