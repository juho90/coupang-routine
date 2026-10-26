$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$previous = @{}
foreach($name in @('DOTNET_CLI_HOME','NUGET_PACKAGES','DOTNET_SKIP_FIRST_TIME_EXPERIENCE','DOTNET_CLI_TELEMETRY_OPTOUT','DOTNET_ADD_GLOBAL_TOOLS_TO_PATH','DOTNET_GENERATE_ASPNET_CERTIFICATE','DOTNET_NOLOGO')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
}
try {
    $env:DOTNET_CLI_HOME = Join-Path $root 'obj/dotnet-home'
    $env:NUGET_PACKAGES = Join-Path $root 'obj/packages'
    $env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
    $env:DOTNET_ADD_GLOBAL_TOOLS_TO_PATH = '0'
    $env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
    $env:DOTNET_NOLOGO = '1'
    & (Get-Command dotnet -ErrorAction Stop).Source build (Join-Path $root 'mock\MockAdb.csproj') --configuration Release --output (Join-Path $root 'bin/mock') --configfile (Join-Path $root 'NuGet.Config') --nologo
    if($LASTEXITCODE -ne 0) { throw 'Mock build failed.' }
} finally {
    foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
