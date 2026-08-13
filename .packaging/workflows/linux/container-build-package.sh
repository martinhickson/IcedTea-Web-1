#!/usr/bin/env bash
set -euo pipefail

FORMAT="${1:?Usage: container-build-package.sh deb|rpm}"
ROOT_DIR="${ITW_WORKSPACE:-/workspace}"
VERSION="${ITW_VERSION:-2.0.1-SNAPSHOT}"
DIST_DIR="${ITW_DIST_DIR:-$ROOT_DIR/icedtea-web-distribution/target/dist/icedtea-web-$VERSION}"
OUTPUT_DIR="${ITW_NATIVE_OUTPUT_DIR:-$ROOT_DIR/icedtea-web-distribution/target/native-packages}"
PACKAGE_NAME="${ITW_PACKAGE_NAME:-icedtea-web}"
MAINTAINER="${ITW_PACKAGE_MAINTAINER:-Martin Hickson <martinhickson@users.noreply.github.com>}"
DESCRIPTION="${ITW_PACKAGE_DESCRIPTION:-IcedTea-Web Java Web Start launcher with bundled .NET launcher and Eclipse Temurin 11, 17, 21, and 25}"
LONG_DESCRIPTION="This package installs the Maven-built IcedTea-Web distribution, including
 the self-contained .NET launcher and bundled Eclipse Temurin 11, 17, 21,
 and 25 JREs under /opt/icedtea-web/runtime."
DEB_ARCH="${ITW_DEB_ARCH:-amd64}"
RPM_ARCH="${ITW_RPM_ARCH:-x86_64}"
INSTALL_ROOT="/opt/icedtea-web"

if [[ ! -d "$DIST_DIR" ]]; then
  echo "Distribution directory not found: $DIST_DIR" >&2
  exit 1
fi

if [[ ! -x "$DIST_DIR/bin/javaws" ]]; then
  echo "Distribution does not contain executable bin/javaws: $DIST_DIR" >&2
  exit 1
fi

if [[ ! -x "$DIST_DIR/bin/javawsc" ]]; then
  echo "Distribution does not contain console launcher bin/javawsc: $DIST_DIR" >&2
  exit 1
fi

ICON_PNG="$ROOT_DIR/.packaging/workflows/icons/icedtea-web.png"
if [[ ! -f "$ICON_PNG" ]]; then
  echo "Launcher icon not found: $ICON_PNG" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

safe_rpm_version() {
  echo "$VERSION" | tr '-' '_'
}

install_doc_files() {
  local payload_root="$1"
  local doc_dir="$payload_root/usr/share/doc/$PACKAGE_NAME"
  local changelog
  mkdir -p "$doc_dir"
  {
    cat <<EOF
IcedTea-Web
https://github.com/martinhickson/IcedTea-Web-1

Copyright 2008-2026 Red Hat, Inc. and contributors.
License: GPL-2 with exceptions / LGPL as described below.

Bundled Eclipse Temurin JREs under /opt/icedtea-web/runtime/temurin-*
are from Adoptium and remain under their own licenses.

EOF
    cat "$ROOT_DIR/LICENSE"
    echo
    cat "$ROOT_DIR/COPYING"
  } > "$doc_dir/copyright"
  chmod 644 "$doc_dir/copyright"

  command -v gzip >/dev/null 2>&1 || {
    echo "gzip is required to ship Debian changelog docs." >&2
    exit 1
  }
  changelog="$(mktemp)"
  cat > "$changelog" <<EOF
$PACKAGE_NAME ($VERSION) unstable; urgency=medium

  * Package IcedTea-Web with a self-contained .NET launcher and bundled
    Eclipse Temurin 11, 17, 21, and 25 JREs.

 -- $MAINTAINER  $(date -R)
EOF
  gzip -9n -c "$changelog" > "$doc_dir/changelog.Debian.gz"
  rm -f "$changelog"
  chmod 644 "$doc_dir/changelog.Debian.gz"
  if [[ -f "$ROOT_DIR/NEWS" ]]; then
    gzip -9n -c "$ROOT_DIR/NEWS" > "$doc_dir/changelog.gz"
    chmod 644 "$doc_dir/changelog.gz"
  fi
}

install_hicolor_icons() {
  local payload_root="$1"
  local icon_name="$2"
  local size
  for size in 16 32 48 64 128 256; do
    local src="$ROOT_DIR/.packaging/workflows/icons/icedtea-web-${size}.png"
    local dest_dir="$payload_root/usr/share/icons/hicolor/${size}x${size}/apps"
    if [[ -f "$src" ]]; then
      mkdir -p "$dest_dir"
      cp "$src" "$dest_dir/${icon_name}.png"
    fi
  done
}

create_payload_root() {
  local payload_root="$1"
  rm -rf "$payload_root"
  mkdir -p "$payload_root$INSTALL_ROOT" "$payload_root/usr/bin" "$payload_root/usr/share/applications" "$payload_root/usr/share/pixmaps"
  cp -a "$DIST_DIR/." "$payload_root$INSTALL_ROOT/"
  chmod +x "$payload_root$INSTALL_ROOT/bin/javaws" "$payload_root$INSTALL_ROOT/bin/javawsc" "$payload_root$INSTALL_ROOT/bin/icedtea-web-settings" "$payload_root$INSTALL_ROOT/bin/policyeditor"
  # 2.9.x compatibility name under /opt (symlink, not a second copy).
  if [[ ! -e "$payload_root$INSTALL_ROOT/bin/itweb-settings" ]]; then
    ln -s icedtea-web-settings "$payload_root$INSTALL_ROOT/bin/itweb-settings"
  fi
  # Temurin ProcessBuilder helper lives in lib/, not bin/.
  find "$payload_root$INSTALL_ROOT/runtime" -type f -name jspawnhelper -exec chmod 755 {} +
  "$ROOT_DIR/scripts/assert-jspawnhelper-executable.sh" "$payload_root$INSTALL_ROOT"
  ln -s "$INSTALL_ROOT/bin/javaws" "$payload_root/usr/bin/javaws"
  ln -s "$INSTALL_ROOT/bin/javawsc" "$payload_root/usr/bin/javawsc"
  ln -s "$INSTALL_ROOT/bin/icedtea-web-settings" "$payload_root/usr/bin/icedtea-web-settings"
  ln -s "$INSTALL_ROOT/bin/icedtea-web-settings" "$payload_root/usr/bin/itweb-settings"
  ln -s "$INSTALL_ROOT/bin/policyeditor" "$payload_root/usr/bin/policyeditor"
  if [[ ! -e "$payload_root$INSTALL_ROOT/bin/itweb-settings" || ! -L "$payload_root/usr/bin/itweb-settings" ]]; then
    echo "Compatibility alias itweb-settings was not installed under $INSTALL_ROOT/bin or /usr/bin" >&2
    exit 1
  fi
  cp "$ICON_PNG" "$payload_root/usr/share/pixmaps/javaws.png"
  cp "$ICON_PNG" "$payload_root/usr/share/pixmaps/icedtea-web-settings.png"
  cp "$ICON_PNG" "$payload_root/usr/share/pixmaps/policyeditor.png"
  install_hicolor_icons "$payload_root" javaws
  install_hicolor_icons "$payload_root" icedtea-web-settings
  install_hicolor_icons "$payload_root" policyeditor

  cat > "$payload_root/usr/share/applications/icedtea-web-javaws.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=IcedTea-Web Java Web Start
Comment=Launch JNLP applications with IcedTea-Web
Exec=$INSTALL_ROOT/bin/javaws %u
TryExec=$INSTALL_ROOT/bin/javaws
Icon=javaws
Terminal=false
Categories=Network;Utility;
MimeType=application/x-java-jnlp-file;
EOF

  cat > "$payload_root/usr/share/applications/icedtea-web-settings.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=IcedTea-Web Control Panel
Name[de]=IcedTea-Web Systemsteuerung
Name[pl]=Panel sterowania IcedTea-Web
Name[cs]=Ovládací panel IcedTea-Web
GenericName=Control Panel
Comment=Configure IcedTea-Web (javaws and plugin)
Comment[de]=Konfiguriert IcedTea-Web (javaws und Plug-in)
Comment[pl]=Konfiguruj IcedTea-Web (javaws i wtyczkę)
Comment[cs]=Konfigurace aplikace IcedTea-Web (javaws a zásuvný modul)
Exec=$INSTALL_ROOT/bin/icedtea-web-settings
TryExec=$INSTALL_ROOT/bin/icedtea-web-settings
Icon=icedtea-web-settings
Terminal=false
Categories=Settings;Utility;
Keywords=IcedTea;IcedTea-Web;java;javaws;web;start;webstart;jnlp;settings;control panel;
EOF

  cat > "$payload_root/usr/share/applications/icedtea-web-policyeditor.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=IcedTea-Web Policy Editor
GenericName=Policy Tool
Comment=Edit Java Applet policy and permission settings
Exec=$INSTALL_ROOT/bin/policyeditor
TryExec=$INSTALL_ROOT/bin/policyeditor
Icon=policyeditor
Terminal=false
Categories=Settings;Utility;
Keywords=IcedTea;IcedTea-Web;java;javaws;web;start;webstart;jnlp;policy;security;permissions;
EOF

  install_doc_files "$payload_root"
  if [[ ! -f "$payload_root/usr/share/doc/$PACKAGE_NAME/copyright" || ! -f "$payload_root/usr/share/doc/$PACKAGE_NAME/changelog.Debian.gz" ]]; then
    echo "Debian policy docs were not installed under /usr/share/doc/$PACKAGE_NAME" >&2
    exit 1
  fi
}

build_deb() {
  command -v dpkg-deb >/dev/null 2>&1 || {
    echo "dpkg-deb is required inside the DEB builder image." >&2
    exit 1
  }

  local deb_root="$OUTPUT_DIR/debroot"
  local deb_file="$OUTPUT_DIR/${PACKAGE_NAME}_${VERSION}_${DEB_ARCH}.deb"
  create_payload_root "$deb_root"
  mkdir -p "$deb_root/DEBIAN"
  cat > "$deb_root/DEBIAN/control" <<EOF
Package: $PACKAGE_NAME
Version: $VERSION
Section: java
Priority: optional
Architecture: $DEB_ARCH
Maintainer: $MAINTAINER
Description: $DESCRIPTION
 $LONG_DESCRIPTION
EOF
  cat > "$deb_root/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q /usr/share/applications 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor 2>/dev/null || true
fi
EOF
  chmod 755 "$deb_root/DEBIAN/postinst"
  dpkg-deb --build --root-owner-group "$deb_root" "$deb_file"
  rm -rf "$deb_root"
  echo "Built DEB: $deb_file"
}

build_rpm() {
  command -v rpmbuild >/dev/null 2>&1 || {
    echo "rpmbuild is required inside the RPM builder image." >&2
    exit 1
  }

  local rpm_version
  rpm_version="$(safe_rpm_version)"
  local rpm_topdir="$OUTPUT_DIR/rpmbuild"
  local payload_root="$OUTPUT_DIR/rpmroot"
  local source_root="$OUTPUT_DIR/${PACKAGE_NAME}-${rpm_version}"
  rm -rf "$rpm_topdir" "$payload_root" "$source_root"
  mkdir -p "$rpm_topdir/BUILD" "$rpm_topdir/RPMS" "$rpm_topdir/SOURCES" "$rpm_topdir/SPECS" "$rpm_topdir/SRPMS"
  create_payload_root "$payload_root"
  mkdir -p "$source_root"
  cp -a "$payload_root/." "$source_root/"
  tar -C "$OUTPUT_DIR" -czf "$rpm_topdir/SOURCES/${PACKAGE_NAME}-${rpm_version}.tar.gz" "${PACKAGE_NAME}-${rpm_version}"

  cat > "$rpm_topdir/SPECS/${PACKAGE_NAME}.spec" <<EOF
Name: $PACKAGE_NAME
Version: $rpm_version
Release: 1%{?dist}
Summary: $DESCRIPTION
License: GPL-2.0-or-later
URL: https://github.com/martinhickson/IcedTea-Web-1
Source0: %{name}-%{version}.tar.gz

%description
$LONG_DESCRIPTION

%prep
%setup -q

%build

%install
mkdir -p %{buildroot}
cp -a . %{buildroot}/

%files
$INSTALL_ROOT
/usr/bin/javaws
/usr/bin/javawsc
/usr/bin/icedtea-web-settings
/usr/bin/itweb-settings
/usr/bin/policyeditor
/usr/share/applications/icedtea-web-javaws.desktop
/usr/share/applications/icedtea-web-settings.desktop
/usr/share/applications/icedtea-web-policyeditor.desktop
/usr/share/pixmaps/javaws.png
/usr/share/pixmaps/icedtea-web-settings.png
/usr/share/pixmaps/policyeditor.png
/usr/share/icons/hicolor
/usr/share/doc/$PACKAGE_NAME
EOF

  rpmbuild -bb --target "$RPM_ARCH" --define "_topdir $rpm_topdir" "$rpm_topdir/SPECS/${PACKAGE_NAME}.spec"
  find "$rpm_topdir/RPMS" -name '*.rpm' -exec cp {} "$OUTPUT_DIR/" \;
  rm -rf "$payload_root" "$source_root"
  echo "Built RPM artifacts in: $OUTPUT_DIR"
}

case "$FORMAT" in
  deb) build_deb ;;
  rpm) build_rpm ;;
  *)
    echo "Unsupported native package format: $FORMAT" >&2
    exit 1
    ;;
esac
