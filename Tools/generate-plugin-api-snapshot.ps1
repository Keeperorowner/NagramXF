$ErrorActionPreference = 'Stop'

$outputPath = Join-Path $PSScriptRoot '..\docs\plugin-api-reference-current-snapshot.md'
$outputPath = [System.IO.Path]::GetFullPath($outputPath)

$paths = @(
    '/docs', '/docs/setup', '/docs/first-plugin',
    '/docs/elyx', '/docs/elyx/quick-start', '/docs/elyx/elyxbuilder',
    '/docs/elyx/project-structure', '/docs/elyx/metadata',
    '/docs/elyx/modules-and-imports', '/docs/elyx/assets',
    '/docs/elyx/localization', '/docs/elyx/settings',
    '/docs/elyx/dependencies', '/docs/elyx/development',
    '/docs/elyx/public-api', '/docs/elyx/troubleshooting',
    '/docs/plugin-class', '/docs/multi-account', '/docs/plugin-settings',
    '/docs/class-proxy', '/docs/xposed-hooking', '/docs/android-utils',
    '/docs/client-utils', '/docs/text-formatting', '/docs/hook-utils',
    '/docs/file-utils', '/docs/intents', '/docs/alert-dialog-builder',
    '/docs/bulletin-helper', '/docs/dev-server', '/docs/pip',
    '/docs/available-libraries', '/docs/common-source-classes'
)

$sha256 = [System.Security.Cryptography.SHA256]::Create()
$utf8 = [System.Text.UTF8Encoding]::new($false)
$records = [System.Collections.Generic.List[object]]::new()
$capture = [System.DateTime]::UtcNow.ToString('o')

foreach ($path in $paths) {
    $url = "https://plugins.exteragram.app$path"
    $request = [System.Net.HttpWebRequest]::Create($url)
    $request.UserAgent = 'NagramXF-plugin-api-snapshot/1.0'
    $response = $request.GetResponse()
    $stream = $response.GetResponseStream()
    $memory = [System.IO.MemoryStream]::new()
    $stream.CopyTo($memory)
    $bytes = $memory.ToArray()
    $hash = ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    $contentType = $response.ContentType

    if ([int]$response.StatusCode -ne 200) {
        throw "Unexpected HTTP status $([int]$response.StatusCode) for $url"
    }

    $records.Add([pscustomobject]@{
        Path = $path
        Url = $url
        Status = [int]$response.StatusCode
        Type = $contentType
        Bytes = $bytes.Length
        Hash = $hash
        Data = $bytes
    })
    $memory.Dispose()
    $stream.Dispose()
    $response.Dispose()
}

$parts = [System.Collections.Generic.List[byte[]]]::new()
function Add-Text([string]$text) {
    $parts.Add($utf8.GetBytes($text))
}

Add-Text "# exteraGram Plugin API Current Website Snapshot`n`n"
Add-Text "> This file contains raw UTF-8 HTML responses captured from the current website pages. It is a point-in-time snapshot, not a live view; re-run the generator after website changes.`n`n"
Add-Text "> Capture time (UTC): $capture`n`n"
Add-Text "## Manifest`n`n"
Add-Text "| # | Path | HTTP status | Content-Type | Bytes | SHA-256 |`n|---:|---|---:|---|---:|---|`n"

$number = 0
foreach ($record in $records) {
    $number++
    Add-Text "| $number | ``$($record.Path)`` | $($record.Status) | ``$($record.Type)`` | $($record.Bytes) | ``$($record.Hash)`` |`n"
}

Add-Text "`n## Raw pages`n`n"
$number = 0
foreach ($record in $records) {
    $number++
    Add-Text "### $number. ``$($record.Path)```n`n"
    Add-Text "- Source: <$($record.Url)>`n"
    Add-Text "- HTTP status: $($record.Status)`n"
    Add-Text "- Content-Type: ``$($record.Type)```n"
    Add-Text "- Byte length: $($record.Bytes)`n"
    Add-Text "- SHA-256: ``$($record.Hash)```n`n"
    Add-Text "````````html`n"
    $parts.Add($record.Data)
    Add-Text "`n`````````n`n"
}

$stream = [System.IO.MemoryStream]::new()
foreach ($part in $parts) {
    $stream.Write($part, 0, $part.Length)
}
[System.IO.File]::WriteAllBytes($outputPath, $stream.ToArray())
$stream.Dispose()
$sha256.Dispose()

"wrote=$outputPath pages=$($records.Count)"
