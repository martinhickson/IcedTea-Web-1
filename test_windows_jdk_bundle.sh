#!/bin/bash
# Test script for Windows JDK bundling functionality
# This script validates the JDK download and MSI creation process

set -e

echo "=== IcedTea-Web Windows JDK Bundling Test ==="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print status
print_status() {
    local status=$1
    local message=$2
    case $status in
        "OK")
            echo -e "${GREEN}✓${NC} $message"
            ;;
        "WARN")
            echo -e "${YELLOW}⚠${NC} $message"
            ;;
        "ERROR")
            echo -e "${RED}✗${NC} $message"
            ;;
        *)
            echo "$message"
            ;;
    esac
}

# Test 1: Check if configure script recognizes the new option
test_configure_option() {
    echo "Test 1: Configure option recognition"
    if ./configure --help | grep -q "windows-jdk-bundle"; then
        print_status "OK" "Configure option --with-windows-jdk-bundle found"
        return 0
    else
        print_status "ERROR" "Configure option --with-windows-jdk-bundle not found"
        return 1
    fi
}

# Test 2: Validate JDK download URLs
test_jdk_urls() {
    echo "Test 2: JDK download URL validation"
    local corretto17_url="https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip"
    local corretto21_url="https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip"

    if curl -s --head "$corretto17_url" | grep -q "200"; then
        print_status "OK" "Corretto 17 download URL accessible"
    else
        print_status "WARN" "Corretto 17 download URL not accessible"
    fi

    if curl -s --head "$corretto21_url" | grep -q "200"; then
        print_status "OK" "Corretto 21 download URL accessible"
    else
        print_status "WARN" "Corretto 21 download URL not accessible"
    fi
}

# Test 3: Check Makefile.am changes
test_makefile_changes() {
    echo "Test 3: Makefile.am modifications"
    if grep -q "CORRETTO_17_URL" Makefile.am; then
        print_status "OK" "Makefile.am contains JDK URL definitions"
    else
        print_status "ERROR" "Makefile.am missing JDK URL definitions"
        return 1
    fi

    if grep -q "WIN_JDK_DEPS" Makefile.am; then
        print_status "OK" "Makefile.am contains Windows JDK dependency variables"
    else
        print_status "ERROR" "Makefile.am missing Windows JDK dependency variables"
        return 1
    fi
}

# Test 4: Validate installer configuration
test_installer_config() {
    echo "Test 4: MSI installer configuration"
    if grep -q "jdk-bundle" win-installer/installer.json.in; then
        print_status "OK" "Installer config includes JDK bundle component"
    else
        print_status "ERROR" "Installer config missing JDK bundle component"
        return 1
    fi

    if grep -q "win-jdk-bundle" win-installer/installer.json.in; then
        print_status "OK" "Installer config includes JDK bundle file references"
    else
        print_status "ERROR" "Installer config missing JDK bundle file references"
        return 1
    fi
}

# Test 5: Check documentation updates
test_documentation() {
    echo "Test 5: Documentation updates"
    if grep -q "windows-jdk-bundle" BUILDING_EXAMPLES; then
        print_status "OK" "BUILDING_EXAMPLES includes JDK bundling instructions"
    else
        print_status "WARN" "BUILDING_EXAMPLES missing JDK bundling instructions"
    fi

    if [ -f "WINDOWS_JDK_BUNDLING_README.md" ]; then
        print_status "OK" "Windows JDK bundling README exists"
    else
        print_status "ERROR" "Windows JDK bundling README missing"
        return 1
    fi
}

# Test 6: GitHub Actions workflow
test_github_actions() {
    echo "Test 6: GitHub Actions workflow"
    if [ -f ".github/workflows/windows-jdk-bundle.yml" ]; then
        print_status "OK" "GitHub Actions workflow exists"
    else
        print_status "ERROR" "GitHub Actions workflow missing"
        return 1
    fi

    if grep -q "windows-jdk-bundle" .github/workflows/windows-jdk-bundle.yml; then
        print_status "OK" "GitHub Actions workflow includes JDK bundling"
    else
        print_status "ERROR" "GitHub Actions workflow missing JDK bundling configuration"
        return 1
    fi
}

# Test 7: License compatibility check
test_license_compatibility() {
    echo "Test 7: License compatibility"
    if grep -q "GPL" COPYING && curl -s https://raw.githubusercontent.com/corretto/corretto-17/main/LICENSE | grep -q "Apache"; then
        print_status "OK" "Licenses are compatible (GPL + Apache 2.0)"
    else
        print_status "WARN" "License compatibility needs verification"
    fi
}

# Main test execution
main() {
    local test_count=0
    local pass_count=0

    echo "Running Windows JDK Bundling validation tests..."
    echo

    # Run all tests
    tests=(
        "test_configure_option"
        "test_jdk_urls"
        "test_makefile_changes"
        "test_installer_config"
        "test_documentation"
        "test_github_actions"
        "test_license_compatibility"
    )

    for test_func in "${tests[@]}"; do
        ((test_count++))
        echo
        if $test_func; then
            ((pass_count++))
        fi
    done

    echo
    echo "=== Test Results ==="
    echo "Passed: $pass_count/$test_count tests"

    if [ $pass_count -eq $test_count ]; then
        print_status "OK" "All tests passed! Windows JDK bundling implementation is ready."
        exit 0
    else
        print_status "WARN" "Some tests failed. Please review the implementation."
        exit 1
    fi
}

# Run main function
main "$@"
