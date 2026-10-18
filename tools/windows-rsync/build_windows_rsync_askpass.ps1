$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot '../../main/src/main/resources/tools/windows/rsync/askpass.exe'
New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
& $compiler /nologo /target:exe /platform:anycpu /optimize+ "/out:$destination" (Join-Path $PSScriptRoot 'windows_rsync_askpass.cs')
if ($LASTEXITCODE -ne 0) { throw 'Failed to compile Windows rsync askpass' }
