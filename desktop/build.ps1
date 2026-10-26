param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$taskRoot = $projectRoot
# In the delivered workspace the Android project lives under outputs/coupang-routine.
# Keep generated desktop binaries and obj files beside the other workspace artifacts.
if ((Split-Path -Leaf (Split-Path -Parent $projectRoot)) -eq 'outputs' -and (Split-Path -Leaf $projectRoot) -eq 'coupang-routine') {
    $taskRoot = Split-Path -Parent (Split-Path -Parent $projectRoot)
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $taskRoot 'outputs/pc-clipboard-1.0.1'
}
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$intermediatePath = (Join-Path $taskRoot 'work/pc-clipboard-build/obj') + [System.IO.Path]::DirectorySeparatorChar
$binaryPath = (Join-Path $taskRoot 'work/pc-clipboard-build/bin') + [System.IO.Path]::DirectorySeparatorChar
$dotnetCommand = Get-Command dotnet -ErrorAction Stop
& $dotnetCommand.Source publish (Join-Path $PSScriptRoot 'RebuyClipboard.csproj') --configuration Release --runtime win-x64 --self-contained false --output $resolvedOutput --configfile (Join-Path $PSScriptRoot 'NuGet.Config') --nologo "-p:BaseIntermediateOutputPath=$intermediatePath" "-p:MSBuildProjectExtensionsPath=$intermediatePath" "-p:BaseOutputPath=$binaryPath"
if ($LASTEXITCODE -ne 0) { throw 'PC 동반 앱 빌드에 실패했습니다.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $resolvedOutput 'README.md') -Force
Write-Output 'PC 동반 앱 빌드 완료. RebuyClipboard.exe를 실행하세요.'
