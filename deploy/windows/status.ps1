<#
.SYNOPSIS
    Показывает состояние бота: задача планировщика, процесс, память, последние строки лога.
    Права администратора не нужны.
#>
param(
    [string]$InstallDir = $(if (Test-Path 'D:\') { 'D:\TaskReminderBot' } else { 'C:\TaskReminderBot' }),
    [string]$TaskName = 'TaskReminderBot',
    [int]$Lines = 30
)

. "$PSScriptRoot\common.ps1"

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if (-not $task) {
    Write-Host "Задача '$TaskName' не установлена (install.ps1)." -ForegroundColor Yellow
} else {
    $info = $task | Get-ScheduledTaskInfo
    Write-Host ("Задача:   {0}, последний запуск {1}" -f $task.State, $info.LastRunTime)
}

$procs = @(Get-ServiceBotProcesses)
if ($procs.Count -eq 0) {
    Write-Host 'Процесс:  не запущен' -ForegroundColor Red
} else {
    foreach ($p in $procs) {
        $mem = [math]::Round((Get-Process -Id $p.ProcessId).WorkingSet64 / 1MB)
        Write-Host ("Процесс:  PID {0}, запущен {1}, память {2} МБ" -f $p.ProcessId, $p.CreationDate, $mem) -ForegroundColor Green
    }
}

$jar = Join-Path $InstallDir $JarName
if (Test-Path $jar) {
    Write-Host ("Jar:      {0} ({1})" -f $jar, (Get-Item $jar).LastWriteTime)
}

Write-OtherInstancesWarning
Write-Host ''
Show-LogTail $InstallDir $Lines
