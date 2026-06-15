# deb-builder (local only, gitignored)

Use a separate clone in this folder to build and install a pretend release `.deb`
without disturbing the main working tree.

## Setup

```bash
cd /path/to/IcedTea-Web-1
git clone . deb-builder/IcedTea-Web-deb
cd deb-builder/IcedTea-Web-deb
git checkout 1.8
```

## Build a pretend 2.4.3 package locally

```bash
mvn -P maven-distribution -pl icedtea-web-distribution -am install \
  -Dmaven.test.skip=true -DskipTests \
  -Ditw.dotnet.selfContained=true \
  -Ditw.dotnet.runtime.identifier=linux-x64 \
  -Drelease.version=2.4.3

chmod +x .packaging/workflows/linux/build-native-packages.sh .packaging/workflows/linux/container-build-package.sh
.packaging/workflows/linux/build-native-packages.sh
```

The `.deb` is written to:

`icedtea-web-distribution/target/native-packages/icedtea-web_2.4.3_amd64.deb`

## Install

```bash
sudo dpkg -i icedtea-web-distribution/target/native-packages/icedtea-web_2.4.3_amd64.deb
```

This directory is listed in `.gitignore` and must never be committed.
