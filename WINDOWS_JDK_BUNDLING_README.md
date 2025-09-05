# Windows JDK Bundling for IcedTea-Web

This document describes the Windows JDK bundling feature that allows IcedTea-Web MSI installers to include Amazon Corretto JDK, creating a complete self-contained Java Web Start solution for Windows.

## Overview

The Windows JDK bundling feature addresses a key limitation of IcedTea-Web on Windows: the requirement for users to manually install Java. By bundling Amazon Corretto JDK directly in the MSI installer, Windows users get a complete, working Java Web Start environment with a single installation.

## Features

✅ **Self-contained MSI installer** - No separate Java installation required
✅ **Automatic environment setup** - JAVA_HOME and PATH configured automatically
✅ **Enterprise-friendly** - Single installer for complete deployment
✅ **License compatible** - Uses Apache 2.0 licensed Amazon Corretto
✅ **Version flexibility** - Support for Corretto 17 and 21

## Building with JDK Bundling

### Prerequisites

- Windows build environment (MSYS2, Cygwin, or similar)
- WiX Toolset for MSI creation
- Internet connection for JDK download
- Build dependencies (autoconf, automake, rust, etc.)

### Build Commands

#### Option 1: Bundle Amazon Corretto 17 (Recommended)
```bash
./configure --with-windows-jdk-bundle=corretto-17 \
           --disable-native-plugin \
           --with-wix=C:/PROGRA~2/wix/bin \
           --with-tagsoup=/path/to/tagsoup.jar \
           --with-commonscompress=/path/to/commonscompress.jar \
           --with-pack=/path/to/pack.jar \
           --with-wixgen=/path/to/wixgen.jar \
           --with-rhino=/path/to/rhino.jar \
           --with-mslinks=/path/to/mslinks.jar

make
make install
make win-installer
```

#### Option 2: Bundle Amazon Corretto 21
```bash
./configure --with-windows-jdk-bundle=corretto-21 \
           [other options...]
```

#### Option 3: No JDK bundling (Default)
```bash
./configure --with-windows-jdk-bundle=none \
           [other options...]
```

## How It Works

### 1. Configure Time
- `--with-windows-jdk-bundle=corretto-17` enables JDK bundling
- Configuration validates Windows build environment
- Sets up download URLs for specified Corretto version

### 2. Build Time
- `make install` triggers JDK download and extraction
- Amazon Corretto JDK downloaded from official AWS servers
- JDK extracted to `$(DESTDIR)$(prefix)/win-jdk-bundle/jdk17/`
- All JAR dependencies copied to Windows runtime locations

### 3. MSI Creation
- WiX installer includes JDK component as optional feature
- Feature tree allows users to choose JDK installation
- Environment variables configured automatically

### 4. Installation Time
- MSI installs IcedTea-Web to `C:\Program Files\WebStart\`
- JDK installed to `C:\Program Files\WebStart\win-jdk-bundle\jdk17\`
- Registry keys and file associations created
- `JAVA_HOME` and `PATH` environment variables set

### 5. Runtime Detection

The launchers use a **prioritized detection order** for finding Java:

1. **JAVA_HOME environment variable** (highest priority)
2. **Bundled Windows JDK** (`../win-jdk-bundle/jdk17/`)
3. **Embedded JDK paths** (for portable builds)
4. **Windows registry** (system-installed JDKs)
5. **PATH environment variable** (fallback)

#### Launcher-Specific Detection:
- **Rust launcher**: Automatically detects bundled JDK relative to executable
- **Shell launcher**: Checks bundled JDK path before other methods
- **Batch launcher**: Windows-specific detection with proper path handling
- **Automatic fallback**: If bundled JDK not found, uses existing detection logic

#### Detection Code Examples:

**Rust launcher (`utils.rs`)**:
```rust
// Check for bundled Windows JDK
let mut bundled_path = dirs_paths_helper::current_program_parent().clone();
bundled_path.push("..");
bundled_path.push("win-jdk-bundle");
bundled_path.push("jdk17");
```

**Shell launcher (`launchers.sh.in`)**:
```bash
# Check for bundled Windows JDK
BUNDLED_JDK_PATH="$PORTABLE_ITW_HOME/win-jdk-bundle/jdk17"
if [ -x "$BUNDLED_JDK_PATH/bin/java" ]; then
  CUSTOM_JRE=$BUNDLED_JDK_PATH
fi
```

**Batch launcher (`launchers.bat.in`)**:
```batch
rem Check for bundled Windows JDK
set "BUNDLED_JDK_PATH=%ITW_HOME%\win-jdk-bundle\jdk17"
if exist "%BUNDLED_JDK_PATH%\bin\java.exe" (
  set "CUSTOM_JRE=%BUNDLED_JDK_PATH%"
)
```

## File Structure

After installation:
```
C:\Program Files\WebStart\
├── bin\
│   ├── javaws.exe
│   ├── itweb-settings.exe
│   └── policyeditor.exe
├── lib\
│   └── [IcedTea-Web JARs]
├── share\
│   └── [resources]
└── win-jdk-bundle\
    └── jdk17\
        ├── bin\
        ├── lib\
        ├── include\
        └── [full JDK]
```

## Environment Variables

The MSI installer automatically configures:

```batch
JAVA_HOME=C:\Program Files\WebStart\win-jdk-bundle\jdk17
PATH=%JAVA_HOME%\bin;%PATH%
```

## Testing

### Automated Testing (GitHub Actions)

The included GitHub Actions workflow (`windows-jdk-bundle.yml`) provides:

1. **Build verification** - Ensures MSI is created successfully
2. **JDK download validation** - Confirms JDK is downloaded and extracted
3. **MSI content verification** - Checks JDK files are included
4. **Installation testing** - Validates MSI installs correctly
5. **Runtime verification** - Tests Java Web Start functionality

### Manual Testing

```powershell
# Install MSI
msiexec /i icedtea-web-with-jdk.msi /quiet

# Verify installation
javaws -version
java -version
echo $env:JAVA_HOME
```

## Benefits

### For Users
- **One-click installation** - Complete Java Web Start environment
- **No dependency issues** - Java included automatically
- **Enterprise ready** - Single MSI for corporate deployment

### For Administrators
- **Predictable deployment** - Known Java version included
- **Security** - Controlled JDK version and updates
- **Support** - Single vendor for complete solution

### For Developers
- **Simplified builds** - Automated JDK integration
- **Version control** - Specific JDK versions per build
- **Testing** - Consistent environment across builds

## Technical Details

### JDK Download URLs
- **Corretto 17**: `https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip`
- **Corretto 21**: `https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip`

### Size Considerations
- **IcedTea-Web only**: ~10-20MB
- **With Corretto 17**: ~220MB
- **Network requirement**: ~200MB download during build

### Compatibility
- **Windows 10/11**: Fully supported
- **Server editions**: Compatible
- **Existing installations**: Won't conflict with system Java

## Troubleshooting

### Build Issues

#### JDK Download Fails
```bash
# Check network connectivity
curl -I https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip

# Manual download if needed
wget https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip
export CORRETTO_ZIP=/path/to/downloaded.zip
```

#### WiX Toolset Missing
```bash
# Install WiX Toolset
choco install wixtoolset
# Or download from: https://github.com/wixtoolset/wix3/releases
```

### Installation Issues

#### MSI Won't Install
- Check Windows Installer service is running
- Verify administrator privileges
- Check available disk space (~300MB recommended)

#### Java Not Found After Installation
```powershell
# Check environment variables
echo $env:JAVA_HOME
echo $env:PATH

# Manual fix if needed
$env:JAVA_HOME = "C:\Program Files\WebStart\win-jdk-bundle\jdk17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

## Future Enhancements

### Planned Features
- **Multiple JDK versions** - Allow user selection during install
- **Update mechanism** - Automatic JDK updates
- **Size optimization** - JRE-only bundling option
- **Custom JDK builds** - Support for custom JDK configurations

### Configuration Options
```bash
# Future options
--with-jdk-update-strategy=auto    # Automatic updates
--with-jdk-size=jre-only          # Smaller JRE instead of JDK
--with-custom-jdk-url=URL         # Custom JDK download URL
```

## Contributing

### Code Structure
- **configure.ac**: Feature detection and option parsing
- **Makefile.am**: JDK download and MSI bundling logic
- **win-installer/installer.json.in**: MSI configuration
- **.github/workflows/**: CI/CD automation

### Testing
- Unit tests for configuration parsing
- Integration tests for MSI creation
- End-to-end tests for installation and functionality

## License and Legal

- **IcedTea-Web**: GPL v2
- **Amazon Corretto**: Apache 2.0
- **Compatibility**: ✅ License compatible
- **Distribution**: Free for commercial and non-commercial use

## Support

- **Documentation**: This README and BUILDING_EXAMPLES
- **Issues**: GitHub issue tracker
- **Discussions**: IcedTea-Web mailing list
- **CI/CD**: GitHub Actions for automated builds

---

*For questions or issues with Windows JDK bundling, please create an issue on the IcedTea-Web GitHub repository.*
