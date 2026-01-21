#!/bin/bash
set -euo pipefail

FILE_TYPE="$1"

if [ "${FILE_TYPE}" = "zip" ]; then
    echo "Searching for Windows ZIP file..."
    WIN_ZIP=$(find . -name "icedtea-web-*.win.bin.zip" -type f | head -n 1)
    WIN_ZIP="${WIN_ZIP#./}"
    
    if [ -z "${WIN_ZIP}" ]; then
        echo "Error: Could not find Windows ZIP file"
        exit 1
    fi
    
    WIN_ZIP_NAME=$(basename "${WIN_ZIP}")
    echo "win_zip=${WIN_ZIP}" >> "${GITHUB_OUTPUT}"
    echo "win_zip_name=${WIN_ZIP_NAME}" >> "${GITHUB_OUTPUT}"
    echo "Found ZIP: ${WIN_ZIP}"
    
elif [ "${FILE_TYPE}" = "msi" ]; then
    echo "Searching for Windows MSI file..."
    WIN_MSI=$(find win-installer.build -name "icedtea-web-*.msi" -type f 2>/dev/null | head -n 1 || true)
    
    if [ -z "${WIN_MSI}" ]; then
        echo "Error: Could not find Windows MSI file"
        exit 1
    fi
    
    WIN_MSI_NAME=$(basename "${WIN_MSI}")
    echo "win_msi=${WIN_MSI}" >> "${GITHUB_OUTPUT}"
    echo "win_msi_name=${WIN_MSI_NAME}" >> "${GITHUB_OUTPUT}"
    echo "Found MSI: ${WIN_MSI}"
    
else
    echo "Error: Invalid file type '${FILE_TYPE}'. Use 'zip' or 'msi'"
    exit 1
fi

