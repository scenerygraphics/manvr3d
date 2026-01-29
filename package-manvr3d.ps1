# manvr3d Packaging Script
# This script creates a standalone distribution with bundled JDK

# Extract version from build.gradle.kts
Write-Host "Reading version from build.gradle.kts..." -ForegroundColor Yellow
if (-not (Test-Path "build.gradle.kts")) {
    Write-Host "ERROR: build.gradle.kts not found!" -ForegroundColor Red
    exit 1
}

$gradleContent = Get-Content "build.gradle.kts" -Raw
if ($gradleContent -match 'version\s*=\s*"([^"]+)"') {
    $version = $matches[1]
    Write-Host "Detected version: $version" -ForegroundColor Green
} else {
    Write-Host "ERROR: Could not find version in build.gradle.kts" -ForegroundColor Red
    exit 1
}

$jdkPath = $env:JAVA_HOME

$ErrorActionPreference = "Stop"

Write-Host "=== manvr3d Packaging Script ===" -ForegroundColor Cyan
Write-Host "Version: $version" -ForegroundColor Green

# Validate JAVA_HOME
if (-not $jdkPath -or -not (Test-Path $jdkPath)) {
    Write-Host "ERROR: JAVA_HOME not set or invalid. Please set JAVA_HOME environment variable." -ForegroundColor Red
    exit 1
}

# Build the project
Write-Host "`nBuilding project..." -ForegroundColor Yellow
& .\gradlew -PbuildFatJAR=true clean build

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Build failed!" -ForegroundColor Red
    exit 1
}

# Locate the distribution zip (get the most recent one if multiple exist)
$distZip = Get-ChildItem -Path "build\distributions\*.zip" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $distZip) {
    Write-Host "ERROR: Distribution zip not found in build\distributions\" -ForegroundColor Red
    exit 1
}

Write-Host "Found distribution: $($distZip.Name)" -ForegroundColor Green

# Create temp directory for extraction
$tempDir = "build\temp-package"
if (Test-Path $tempDir) {
    Remove-Item -Path $tempDir -Recurse -Force
}
New-Item -ItemType Directory -Path $tempDir | Out-Null

# Extract the distribution
Write-Host "`nExtracting distribution..." -ForegroundColor Yellow
Expand-Archive -Path $distZip.FullName -DestinationPath $tempDir

# Find the extracted folder (should be mastodon-sciview-bridge-* or manvr3d-*)
$extractedDir = Get-ChildItem -Path $tempDir -Directory | Select-Object -First 1

# Create final package directory
$packageName = "manvr3d-v$version-windows-standalone"
$packageDir = "build\distributions\$packageName"

if (Test-Path $packageDir) {
	Write-Host "Found existing packaging with same name. Overwriting..."
    Remove-Item -Path $packageDir -Recurse -Force
}
New-Item -ItemType Directory -Path $packageDir | Out-Null

# Copy bin and lib directories
Write-Host "Copying distribution files..." -ForegroundColor Yellow
Copy-Item -Path "$($extractedDir.FullName)\lib" -Destination "$packageDir\lib" -Recurse
New-Item -ItemType Directory -Path "$packageDir\bin" | Out-Null

# Copy and bundle JDK
Write-Host "Bundling JDK from: $jdkPath" -ForegroundColor Yellow
Copy-Item -Path $jdkPath -Destination "$packageDir\bin\jdk" -Recurse

# Create the custom launcher script
Write-Host "Creating launcher script..." -ForegroundColor Yellow
$launcherScript = @'
@rem
@rem manvr3d launcher script with bundled JDK
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  manvr3d startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here
set DEFAULT_JVM_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED"

@rem Use bundled JDK
set JAVA_EXE=%DIRNAME%jdk\bin\java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: Bundled JDK not found at: %JAVA_EXE%
echo Please ensure the JDK folder is present in the bin directory.
echo.
goto fail

:execute
@rem Setup the command line
set CLASSPATH=%APP_HOME%\lib\*

@rem Check if args file exists
if exist "%APP_HOME%\bin\manvr3d.args" (
    "%JAVA_EXE%" @"%APP_HOME%\bin\manvr3d.args" %DEFAULT_JVM_OPTS% %JAVA_OPTS% -classpath "%CLASSPATH%" org.mastodon.mamut.plugins.StartMastodon %*
) else (
    "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% -classpath "%CLASSPATH%" org.mastodon.mamut.plugins.StartMastodon %*
)

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
'@

$launcherScript | Out-File -FilePath "$packageDir\bin\manvr3d.bat" -Encoding ASCII

# Copy manvr3d.args if it exists in the repository
if (Test-Path "manvr3d.args") {
    Copy-Item -Path "manvr3d.args" -Destination "$packageDir\bin\manvr3d.args"
}

# Copy README
Write-Host "Copying README..." -ForegroundColor Yellow
if (Test-Path "README.md") {
    Copy-Item -Path "README.md" -Destination "$packageDir\README.md"
}

# Copy doc folder if it exists (contains images referenced in README)
if (Test-Path "doc") {
    Write-Host "Copying doc folder..." -ForegroundColor Yellow
    Copy-Item -Path "doc" -Destination "$packageDir\doc" -Recurse
}

# Create a simple instruction file
$instructions = @"
manvr3d v$version - Standalone Windows Distribution
=====================================================

To run manvr3d:
1. Navigate to the 'bin' folder
2. Double-click 'manvr3d.bat' or run it from command line

This distribution includes a bundled JDK, so you don't need Java installed separately.

For more information, see README.md

"@
$instructions | Out-File -FilePath "$packageDir\INSTRUCTIONS.txt" -Encoding UTF8

# Create final zip
Write-Host "`nCreating final package..." -ForegroundColor Yellow
$finalZip = "build\distributions\$packageName.zip"
if (Test-Path $finalZip) {
    Remove-Item -Path $finalZip -Force
}

Compress-Archive -Path $packageDir -DestinationPath $finalZip

# Cleanup temp directory
Remove-Item -Path $tempDir -Recurse -Force

Write-Host "`n=== Packaging Complete! ===" -ForegroundColor Green
Write-Host "Package location: $finalZip" -ForegroundColor Cyan
Write-Host "Package size: $([math]::Round((Get-Item $finalZip).Length / 1MB, 2)) MB" -ForegroundColor Cyan
Write-Host "`nYou can now upload this zip file to GitHub releases." -ForegroundColor Yellow
