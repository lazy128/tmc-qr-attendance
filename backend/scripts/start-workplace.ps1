param(
  [switch]$Initialize,
  [switch]$AllowHttp,
  [string]$FrontendOrigin
)

$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
$temporaryPassword = $false
$previousSecureCookie = $env:TMC_SESSION_SECURE
$previousCorsOrigins = $env:TMC_CORS_ORIGINS
try {
  if (-not $env:TMC_SESSION_KEY) {
    $keyPath = Join-Path (Get-Location) 'data/workplace-session.key'
    if (-not (Test-Path -LiteralPath $keyPath)) {
      [void][IO.Directory]::CreateDirectory((Split-Path -Parent $keyPath))
      $key = New-Object byte[] 32
      $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
      try { $generator.GetBytes($key) } finally { $generator.Dispose() }
      [IO.File]::WriteAllText($keyPath, [Convert]::ToBase64String($key))
    }
    $env:TMC_SESSION_KEY = [IO.File]::ReadAllText($keyPath).Trim()
  }
  if (-not $env:TMC_ADMIN_PASSWORD -and ($Initialize -or -not (Test-Path -LiteralPath './data/tmc-workplace.mv.db'))) {
    $password = Read-Host 'Mật khẩu tạm của admin (ít nhất 12 ký tự)' -AsSecureString
    $env:TMC_ADMIN_PASSWORD = [Net.NetworkCredential]::new('', $password).Password
    $temporaryPassword = $true
  }
  if ($AllowHttp) { $env:TMC_SESSION_SECURE = 'false' }
  if ($FrontendOrigin) { $env:TMC_CORS_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173,$FrontendOrigin" }
  & .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=workplace'
  if ($LASTEXITCODE -ne 0) { throw "Backend dừng với mã $LASTEXITCODE." }
} finally {
  if ($temporaryPassword) { Remove-Item Env:TMC_ADMIN_PASSWORD -ErrorAction SilentlyContinue }
  $env:TMC_SESSION_SECURE = $previousSecureCookie
  $env:TMC_CORS_ORIGINS = $previousCorsOrigins
  Pop-Location
}
