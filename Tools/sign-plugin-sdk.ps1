<#
.SYNOPSIS
    Signs a Python SDK archive for the in-app signature check (SdkSignature).

.DESCRIPTION
    The app verifies the payload "extera-pysdk|<version>|<abi>|<sha256 of archive>" with an ECDSA
    P-256 / SHA-256 key and expects a base64 DER signature. This script builds that payload, signs
    it with the release private key through openssl, and prints the "signature=" line that belongs
    in the SDK update message, plus the public key that has to be compiled into SdkSignature.

.EXAMPLE
    pwsh Tools/sign-plugin-sdk.ps1 -Archive TMessagesProj/src/plugin/assets/plugins_pysdk/sdk-arm64-v8a.zip -Version 1.4.7.7 -Abi arm64-v8a -KeyFile release.pem

.EXAMPLE
    pwsh Tools/sign-plugin-sdk.ps1 -KeyFile release.pem -PrintPublicKey
#>
param(
    [string]$Archive,
    [string]$Version,
    [string]$Abi,
    [Parameter(Mandatory = $true)][string]$KeyFile,
    [switch]$PrintPublicKey
)

$ErrorActionPreference = "Stop"

$openssl = (Get-Command openssl -ErrorAction SilentlyContinue).Source
if (-not $openssl) {
    throw "openssl was not found on PATH"
}

if ($PrintPublicKey) {
    if (-not (Test-Path $KeyFile)) { throw "Key file not found: $KeyFile" }
    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("sdk-pub-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $pubDer = Join-Path $work "public.der"
        & $openssl ec -in $KeyFile -pubout -outform DER -out $pubDer 2>$null
        if ($LASTEXITCODE -ne 0) { throw "Failed to export the public key" }
        $base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($pubDer))
        Write-Output "SdkSignature.PUBLIC_KEY_BASE64 ="
        Write-Output $base64
    } finally {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
    return
}

foreach ($required in @($Archive, $Version, $Abi)) {
    if ([string]::IsNullOrWhiteSpace($required)) {
        throw "Archive, Version and Abi are required unless -PrintPublicKey is used"
    }
}
if (-not (Test-Path $Archive)) { throw "Archive not found: $Archive" }
if (-not (Test-Path $KeyFile)) { throw "Key file not found: $KeyFile" }

$sha256 = (Get-FileHash -Algorithm SHA256 -Path $Archive).Hash.ToLowerInvariant()
$payload = "extera-pysdk|$Version|$Abi|$sha256"

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("sdk-sign-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $payloadFile = Join-Path $work "payload.bin"
    $signatureFile = Join-Path $work "signature.der"
    # The app signs the UTF-8 bytes of the payload exactly as printed, without a trailing newline.
    [System.IO.File]::WriteAllBytes($payloadFile, [System.Text.Encoding]::UTF8.GetBytes($payload))

    & $openssl dgst -sha256 -sign $KeyFile -out $signatureFile $payloadFile 2>$null
    if ($LASTEXITCODE -ne 0) { throw "openssl signing failed" }

    $signature = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($signatureFile))
    Write-Output "payload   : $payload"
    Write-Output "signature= $signature"
    Write-Output ""
    Write-Output "Add this line to the SDK update message (after abi=):"
    Write-Output "signature=$signature"
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
