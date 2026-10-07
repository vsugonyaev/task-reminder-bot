# Общие функции для install.ps1 / update.ps1 / uninstall.ps1 / status.ps1 (Windows PowerShell 5.1+).

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$JarName = 'task-reminder-bot.jar'
# run.cmd запускает java именно так; у запусков из IDEA и из target\ (task-reminder-bot-1.1.0.jar) другая строка
$ServiceJarArg = "-jar $JarName"

function Assert-Admin {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Запустите PowerShell от имени администратора (правый клик -> "Запуск от имени администратора").'
    }
}

# Самый свежий собранный jar в target\ (без original-*.jar от maven-shade-plugin)
function Get-BuiltJar {
    $jar = Get-ChildItem (Join-Path $RepoRoot 'target') -Filter 'task-reminder-bot-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Не найден собранный jar в $RepoRoot\target. Соберите проект: update.ps1 -Build или mvn -DskipTests package."
    }
    return $jar
}

function Get-JavaMajor([string]$javaExe) {
    $line = & cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1
    if ($line -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

# Java 21+: параметр -JavaHome, затем JAVA_HOME, затем D:\Java, Program Files и в последнюю очередь
# JDK, скачанные IntelliJ IDEA (~\.jdks) — IDEA может удалить или обновить их
function Resolve-JavaExe([string]$javaHome) {
    $candidates = @()
    if ($javaHome) { $candidates += $javaHome }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += Get-ChildItem 'D:\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { $_.FullName }
    $candidates += Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { $_.FullName }

    foreach ($dir in $candidates) {
        $exe = Join-Path $dir 'bin\java.exe'
        if ((Test-Path $exe) -and ((Get-JavaMajor $exe) -ge 21)) { return $exe }
    }
    throw 'Не найдена Java 21+. Укажите путь: -JavaHome "C:\путь\к\jdk-21" или установите Temurin 21 (winget install EclipseAdoptium.Temurin.21.JRE).'
}

# java.exe, запущенные службой (run.cmd)
function Get-ServiceBotProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($ServiceJarArg) }
}

# Другие экземпляры бота (из IDEA, из target\, вручную) — с одним токеном может работать только один
function Get-OtherBotProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and -not $_.CommandLine.Contains($ServiceJarArg) } |
        Where-Object { $_.CommandLine -match 'com\.example\.Main|task-reminder-bot-[\d.]+\.jar' }
}

function Stop-Bot([string]$taskName, [string]$installDir) {
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($task -and $task.State -eq 'Running') {
        Stop-ScheduledTask -TaskName $taskName
    }
    # Планировщик останавливает cmd.exe с run.cmd, а java может остаться — завершаем и его
    $runCmd = Join-Path $installDir 'run.cmd'
    Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($runCmd) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-ServiceBotProcesses | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}

function Show-LogTail([string]$installDir, [int]$lines = 25) {
    $log = Join-Path $installDir 'logs\bot.log'
    if (Test-Path $log) {
        Write-Host "--- последние строки $log ---" -ForegroundColor DarkGray
        Get-Content $log -Tail $lines -Encoding UTF8
    } else {
        Write-Host "Лог пока не создан: $log" -ForegroundColor Yellow
    }
}

# Ждёт в логе "Bot started" (успех) или падение main (ошибка запуска)
function Wait-BotStarted([string]$installDir, [datetime]$since, [int]$timeoutSec = 90) {
    $log = Join-Path $installDir 'logs\bot.log'
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        if ((Test-Path $log) -and (Get-Item $log).LastWriteTime -ge $since) {
            # Только строки после последнего "starting bot" (его пишет run.cmd перед каждым запуском)
            $tail = @(Get-Content $log -Tail 200 -Encoding UTF8)
            $start = -1
            for ($i = $tail.Count - 1; $i -ge 0; $i--) {
                if ($tail[$i] -match 'starting bot') { $start = $i; break }
            }
            if ($start -ge 0) {
                $current = $tail[$start..($tail.Count - 1)]
                if ($current -match 'Bot started') { return $true }
                if ($current -match 'Exception in thread "main"') { return $false }
            }
        }
    }
    return $false
}

function Write-OtherInstancesWarning {
    $others = @(Get-OtherBotProcesses)
    if ($others.Count -gt 0) {
        Write-Host ''
        Write-Host 'ВНИМАНИЕ: запущены другие экземпляры бота (IDEA или jar из target). С одним токеном работает только один —' -ForegroundColor Yellow
        Write-Host 'остановите их, иначе будет ошибка 409 Conflict и двойные напоминания:' -ForegroundColor Yellow
        $others | ForEach-Object { Write-Host ("  PID {0}, запущен {1}" -f $_.ProcessId, $_.CreationDate) -ForegroundColor Yellow }
    }
}
