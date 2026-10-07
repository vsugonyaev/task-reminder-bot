<#
.SYNOPSIS
    Обновляет установленного бота: (опционально) собирает проект, заменяет jar, перезапускает.
    config.properties и artifacts.db в папке установки не трогаются.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\windows\update.ps1 -Build
#>
param(
    [string]$InstallDir = $(if (Test-Path 'D:\') { 'D:\TaskReminderBot' } else { 'C:\TaskReminderBot' }),
    [string]$TaskName = 'TaskReminderBot',
    # Собрать jar перед обновлением (mvn -DskipTests package на Java 21)
    [switch]$Build,
    [string]$JavaHome
)

. "$PSScriptRoot\common.ps1"
Assert-Admin

if (-not (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
    throw "Задача '$TaskName' не найдена — сначала выполните install.ps1."
}

if ($Build) {
    $javaExe = Resolve-JavaExe $JavaHome
    $env:JAVA_HOME = Split-Path (Split-Path $javaExe -Parent) -Parent
    Write-Host "Сборка (JAVA_HOME=$env:JAVA_HOME)..." -ForegroundColor Cyan
    Push-Location $RepoRoot
    try {
        & mvn -q -DskipTests clean package
        if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась' }
    } finally {
        Pop-Location
    }
}

$jar = Get-BuiltJar
Write-Host "Новый jar: $($jar.FullName) ($($jar.LastWriteTime))"

Stop-Bot $TaskName $InstallDir
Copy-Item $jar.FullName (Join-Path $InstallDir $JarName) -Force
Copy-Item (Join-Path $PSScriptRoot 'run.cmd') (Join-Path $InstallDir 'run.cmd') -Force

$startedAt = Get-Date
Start-ScheduledTask -TaskName $TaskName
Write-Host 'Перезапускаю и жду старта бота (до 90 сек)...'
if (Wait-BotStarted $InstallDir $startedAt) {
    Write-Host 'Бот обновлён и запущен.' -ForegroundColor Green
} else {
    Write-Host 'Бот не подтвердил запуск — смотрите лог ниже.' -ForegroundColor Red
}
Show-LogTail $InstallDir 15
Write-OtherInstancesWarning
