param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference = 'Stop'
$Executable = (Get-Item -LiteralPath $Executable).FullName
$root = $PSScriptRoot
$repo = Split-Path -Parent (Split-Path -Parent $root)
$fixture = Get-Content -Raw -LiteralPath (Join-Path $repo 'testdata\coupang-product.json') | ConvertFrom-Json
if($fixture.schemaVersion -ne 1 -or [string]::IsNullOrWhiteSpace($fixture.id) -or [string]::IsNullOrWhiteSpace($fixture.productUrl)) { throw 'Invalid shared product fixture.' }
[xml]$project = Get-Content -Raw -LiteralPath (Join-Path $repo 'tools\RebuyClipboard.csproj')
$expectedVersion = [string]$project.Project.PropertyGroup.Version
$mock = Join-Path $root 'bin\mock\adb.exe'
if (-not (Test-Path -LiteralPath $mock)) { throw 'Build mock first with build-mock.ps1.' }
$runRoot = Join-Path $root ('obj/run-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $runRoot
$state = Join-Path $runRoot 'mock-state'
$null = New-Item -ItemType Directory -Path $state
$image = Join-Path $runRoot 'synthetic product image.png'
$invalid = Join-Path $runRoot 'not an image.txt'
Add-Type -AssemblyName System.Drawing
$bitmap = New-Object Drawing.Bitmap 32,24
try {
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([Drawing.Color]::Red)
        $graphics.FillRectangle([Drawing.Brushes]::Blue,16,12,16,12)
    } finally { $graphics.Dispose() }
    $bitmap.Save($image,[Drawing.Imaging.ImageFormat]::Png)
} finally { $bitmap.Dispose() }
[IO.File]::WriteAllText($invalid,'This is not an image.')

function Invoke-Cli([string]$Name,[string[]]$Arguments,[int]$Expected) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.WorkingDirectory = $runRoot
    $info.Environment['REBUY_MOCK_STATE'] = $state
    $info.Environment['REBUY_MOCK_PRODUCT_URL'] = $fixture.productUrl
    foreach($argument in $Arguments) { $null = $info.ArgumentList.Add($argument) }
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if(-not $process.Start()) { throw 'Could not start CLI.' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(20000)) {
            try { $process.Kill($true) } catch {}
            throw ('CLI timed out: '+$Name)
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $stdout | Set-Content -LiteralPath (Join-Path $runRoot ($Name+'.stdout.txt')) -Encoding utf8
        $stderr | Set-Content -LiteralPath (Join-Path $runRoot ($Name+'.stderr.txt')) -Encoding utf8
        if($process.ExitCode -ne $Expected) {
            throw ($Name+' exit '+$process.ExitCode+', expected '+$Expected+'. '+$stderr)
        }
        [pscustomobject]@{Name=$Name;ExitCode=$process.ExitCode;Stdout=$stdout;Stderr=$stderr}
    } finally { $process.Dispose() }
}

$results = @()
$results += Invoke-Cli 'no-arguments' @() 0
$results += Invoke-Cli 'help' @('--help') 0
$results += Invoke-Cli 'version' @('--version') 0
if($results[0].Stdout -notmatch 'RebuyClipboard' -or $results[1].Stdout -notmatch 'RebuyClipboard') { throw 'Help was not written to stdout.' }
if($results[2].Stdout -notmatch [regex]::Escape($expectedVersion)) { throw ('Expected version '+$expectedVersion+' on stdout.') }
$results += Invoke-Cli 'unknown-command' @('unknown-command') 2
$results += Invoke-Cli 'missing-image-value' @('check','--image') 2
$results += Invoke-Cli 'check-valid-image' @('check','--image',$image) 0
$results += Invoke-Cli 'check-invalid-image' @('check','--image',$invalid) 2
$results += Invoke-Cli 'sample-show' @('sample','show') 0
$shownFixture = $results[-1].Stdout | ConvertFrom-Json
if(($shownFixture | ConvertTo-Json -Depth 8 -Compress) -cne ($fixture | ConvertTo-Json -Depth 8 -Compress)) { throw 'Published sample differs from the shared fixture.' }
if(Test-Path -LiteralPath (Join-Path $state 'argv.jsonl')) { throw 'Validation unexpectedly invoked ADB.' }

$results += Invoke-Cli 'send-mock-image' @('send','--image',$image,'--adb',$mock,'--device','127.0.0.1:5555') 0
$payload = Join-Path $state 'received.png'
if(-not (Test-Path -LiteralPath $payload)) { throw 'Mock did not receive PNG.' }
$received = [Drawing.Bitmap]::new($payload)
try {
    if($received.Width -ne 32 -or $received.Height -ne 24) { throw 'Unexpected received PNG dimensions.' }
    if($received.GetPixel(0,0).ToArgb() -ne [Drawing.Color]::Red.ToArgb() -or $received.GetPixel(31,23).ToArgb() -ne [Drawing.Color]::Blue.ToArgb()) { throw 'Received PNG pixels differ.' }
} finally { $received.Dispose() }
$results += Invoke-Cli 'devices-mock' @('devices','--adb',$mock) 0
if($results[-1].Stdout -notmatch '127\.0\.0\.1:5555') { throw 'Device was not shown.' }
$results += Invoke-Cli 'sample-open-mock' @('sample','open','--adb',$mock,'--device','127.0.0.1:5555') 0
$calls = @(Get-Content -LiteralPath (Join-Path $state 'argv.jsonl'))
if($calls.Count -ne 11) { throw ('Expected exactly 11 validated mock invocations, got '+$calls.Count) }
$summary = [ordered]@{
    Executable=$Executable
    CompletedAt=(Get-Date).ToString('o')
    Cases=$results
    SampleId=$fixture.id
    SampleProductUrl=$fixture.productUrl
    MockAdbCalls=$calls.Count
    ReceivedBytes=(Get-Item -LiteralPath $payload).Length
    ReceivedSHA256=(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash
    RealAdbUsed=$false
    ClipboardRead=$false
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runRoot 'results.json') -Encoding utf8
[pscustomobject]@{Passed=$results.Count;MockAdbCalls=$calls.Count;Results=(Join-Path $runRoot 'results.json')}|ConvertTo-Json -Compress
