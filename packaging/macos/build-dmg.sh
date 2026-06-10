#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${ITW_VERSION:-2.0.1-SNAPSHOT}"
DIST_DIR="${ITW_DIST_DIR:-$ROOT_DIR/icedtea-web-distribution/target/dist/icedtea-web-$VERSION}"
OUTPUT_DIR="${ITW_NATIVE_OUTPUT_DIR:-$ROOT_DIR/icedtea-web-distribution/target/native-packages}"
RID="${ITW_MACOS_RID:-osx-x64}"

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

mkdir -p "$OUTPUT_DIR"

APP_NAME="IcedTea-Web"
STAGING="$OUTPUT_DIR/dmg-staging"
APP_ROOT="$STAGING/$APP_NAME.app/Contents"
DMG_LAYOUT="$STAGING/dmg-layout"
DMG_FILE="$OUTPUT_DIR/icedtea-web-${VERSION}-${RID}.dmg"

rm -rf "$STAGING"
mkdir -p "$APP_ROOT/MacOS" "$APP_ROOT/Resources/opt/icedtea-web" "$DMG_LAYOUT"

cp -a "$DIST_DIR/." "$APP_ROOT/Resources/opt/icedtea-web/"
chmod +x "$APP_ROOT/Resources/opt/icedtea-web/bin/"* 2>/dev/null || true

ICON_SCRIPT="$ROOT_DIR/packaging/macos/prepare-macos-icon.sh"
ICON_ICNS="$ROOT_DIR/packaging/icons/icedtea-web.icns"
if [[ -x "$ICON_SCRIPT" ]]; then
  "$ICON_SCRIPT"
fi
if [[ -f "$ICON_ICNS" ]]; then
  cp "$ICON_ICNS" "$APP_ROOT/Resources/icedtea-web.icns"
fi

cat > "$APP_ROOT/MacOS/javaws" <<'EOF'
#!/usr/bin/env bash
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$APP_DIR/Resources/opt/icedtea-web/bin/javaws" "$@"
EOF

cat > "$APP_ROOT/MacOS/itweb-settings" <<'EOF'
#!/usr/bin/env bash
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$APP_DIR/Resources/opt/icedtea-web/bin/itweb-settings" "$@"
EOF

cat > "$APP_ROOT/MacOS/javawsc" <<'EOF'
#!/usr/bin/env bash
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$APP_DIR/Resources/opt/icedtea-web/bin/javawsc" "$@"
EOF

cat > "$APP_ROOT/MacOS/policyeditor" <<'EOF'
#!/usr/bin/env bash
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$APP_DIR/Resources/opt/icedtea-web/bin/policyeditor" "$@"
EOF

chmod +x "$APP_ROOT/MacOS/javaws" "$APP_ROOT/MacOS/itweb-settings" "$APP_ROOT/MacOS/javawsc" "$APP_ROOT/MacOS/policyeditor"

ICON_PLIST=""
if [[ -f "$APP_ROOT/Resources/icedtea-web.icns" ]]; then
  ICON_PLIST="$(cat <<'PLIST'

  <key>CFBundleIconFile</key>
  <string>icedtea-web</string>
PLIST
)"
fi

cat > "$APP_ROOT/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>
  <string>IcedTea-Web</string>
  <key>CFBundleDisplayName</key>
  <string>IcedTea-Web</string>
  <key>CFBundleIdentifier</key>
  <string>net.sourceforge.icedtea-web</string>
  <key>CFBundleVersion</key>
  <string>${VERSION}</string>
  <key>CFBundleShortVersionString</key>
  <string>${VERSION}</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleExecutable</key>
  <string>javaws</string>${ICON_PLIST}
  <key>CFBundleDocumentTypes</key>
  <array>
    <dict>
      <key>CFBundleTypeExtensions</key>
      <array><string>jnlp</string></array>
      <key>CFBundleTypeName</key>
      <string>Java Web Start</string>
      <key>CFBundleTypeRole</key>
      <string>Viewer</string>
    </dict>
  </array>
</dict>
</plist>
EOF

cp -R "$STAGING/$APP_NAME.app" "$DMG_LAYOUT/"
ln -s /Applications "$DMG_LAYOUT/Applications"

hdiutil create \
  -volname "IcedTea-Web ${VERSION}" \
  -srcfolder "$DMG_LAYOUT" \
  -ov \
  -format UDZO \
  "$DMG_FILE"

rm -rf "$STAGING"
echo "Built macOS DMG: $DMG_FILE"
