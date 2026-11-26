param([switch]$IsolatedData, [switch]$Check)

$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$jar = 'target/qr-attendance-edge-1.0.0.jar'
if (-not (Test-Path -LiteralPath (Join-Path $backend $jar))) {
    throw 'Chạy mvnw package trong backend trước.'
}

$run = New-Item -ItemType Directory -Path (Join-Path $backend ('target/demo-' + [guid]::NewGuid()))
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool' }
$previousPassword = $env:TMC_QR_KEYSTORE_PASSWORD
$previousHr = $env:TMC_HR_DEMO_TOKEN
$previousEmployee = $env:TMC_EMPLOYEE_DEMO_TOKEN
$previousProof = $env:TMC_DEVICE_PROOF
$env:TMC_QR_KEYSTORE_PASSWORD = [guid]::NewGuid().ToString('N')
$children = @()
if ($Check) {
    $IsolatedData = $true
    $env:TMC_HR_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
    $env:TMC_EMPLOYEE_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
}

function Start-DemoProcess($name, $arguments) {
    $settings = @{
        FilePath = $java
        WorkingDirectory = $backend
        WindowStyle = 'Hidden'
        PassThru = $true
        ArgumentList = @('-jar', $jar) + $arguments
        RedirectStandardOutput = Join-Path $run.FullName "$name.log"
        RedirectStandardError = Join-Path $run.FullName "$name.err"
    }
    Start-Process @settings
}

try {
    $keystore = Join-Path $run.FullName 'demo.p12'
    $keyArgs = @('-genkeypair', '-alias', 'demo', '-keyalg', 'EC', '-groupname', 'secp256r1',
        '-validity', '30', '-dname', 'CN=TMC Local Demo', '-storetype', 'PKCS12',
        '-keystore', $keystore, '-storepass:env', 'TMC_QR_KEYSTORE_PASSWORD')
    & $keytool @keyArgs
    if ($LASTEXITCODE -ne 0) { throw 'Không tạo được khóa ký QR demo.' }

    $children += Start-DemoProcess 'central' @('--server.port=8090', '--server.address=127.0.0.1',
        '--spring.datasource.url=jdbc:h2:mem:central_demo', '--tmc.central.enabled=false')
    foreach ($site in 'bd', 'la') {
        $arguments = @("--spring.profiles.active=$site", ('"--tmc.qr.keystore={0}"' -f $keystore))
        if ($IsolatedData) { $arguments += "--spring.datasource.url=jdbc:h2:mem:edge_$site" }
        $children += Start-DemoProcess $site $arguments
    }
    Write-Host "BD :8081, LA :8082, Central :8090. Log: $($run.FullName). Ctrl+C để dừng."
    if ($Check) {
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        foreach ($port in 8081, 8082, 8090) {
            while ($true) {
                if ($children | Where-Object HasExited) { throw "Tiến trình demo đã dừng. Xem $($run.FullName)." }
                try {
                    $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
                    break
                } catch {
                    if ([DateTime]::UtcNow -ge $deadline) { throw "Cổng $port chưa sẵn sàng. Xem $($run.FullName)." }
                    Start-Sleep -Milliseconds 500
                }
            }
        }
        $enroll = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/qr/v1/credentials/demo-credential/devices' -Headers @{
            Authorization = "Bearer $env:TMC_HR_DEMO_TOKEN"
        }
        $env:TMC_DEVICE_PROOF = $enroll.deviceProof
        & node (Join-Path $PSScriptRoot 'check-multisite.mjs')
        if ($LASTEXITCODE -ne 0) { throw 'Kiểm tra đồng bộ hai site thất bại.' }
        return
    }
    while (-not ($children | Where-Object HasExited)) { Start-Sleep -Seconds 1 }
    throw "Một tiến trình demo đã dừng. Xem log tại $($run.FullName)."
} finally {
    foreach ($child in $children) {
        if (-not $child.HasExited) { Stop-Process -InputObject $child }
    }
    $env:TMC_QR_KEYSTORE_PASSWORD = $previousPassword
    $env:TMC_HR_DEMO_TOKEN = $previousHr
    $env:TMC_EMPLOYEE_DEMO_TOKEN = $previousEmployee
    $env:TMC_DEVICE_PROOF = $previousProof
}
