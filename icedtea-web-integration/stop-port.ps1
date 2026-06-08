# PowerShell script to stop Java processes using a specific port
param(
    [Parameter(Mandatory=$false)]
    [int]$Port = 18080
)

Write-Host "Checking for processes on port $Port..."

# Get PIDs using the port
$pids = netstat -ano | findstr ":$Port" | ForEach-Object { 
    $parts = $_ -split '\s+'
    $parts[-1]
} | Select-Object -Unique

if ($pids.Count -eq 0) {
    Write-Host "No processes found on port $Port"
    exit 0
}

Write-Host "Found $($pids.Count) process(es) on port $Port"

foreach ($processId in $pids) {
    try {
        $proc = Get-Process -Id $processId -ErrorAction Stop
        $isJava = $proc.ProcessName -like "*java*" -or $proc.ProcessName -like "*javaw*" -or $proc.ProcessName -like "*javaws*"
        if ($isJava) {
            Write-Host "Stopping Java process: PID $processId ($($proc.ProcessName))"
            Stop-Process -Id $processId -Force
            Write-Host "  Process stopped"
        } else {
            Write-Host "Skipping non-Java process: PID $processId ($($proc.ProcessName))"
        }
    } catch {
        Write-Host "  Process $processId not found or already stopped"
    }
}

Write-Host "Done"
