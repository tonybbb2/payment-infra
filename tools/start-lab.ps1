$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    # Reuse the project's Jackson dependency; the console does not start Spring.
    & .\mvnw.cmd -q dependency:build-classpath '-Dmdep.outputFile=target/lab-classpath.txt' '-DincludeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the console classpath.' }
    $labClasspath = (Get-Content -Raw -LiteralPath 'target/lab-classpath.txt').Trim()
    & java --class-path $labClasspath tools/PaymentLab.java
    if ($LASTEXITCODE -ne 0) { throw 'Lab console exited with an error.' }
} finally {
    Pop-Location
}
