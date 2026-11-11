$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force build/classes | Out-Null
$sources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -d build/classes $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp build/classes chatapp.server.ChatServer @args
