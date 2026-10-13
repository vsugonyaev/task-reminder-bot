<#
.SYNOPSIS
    Показывает состояние бота: задача планировщика, процесс, память, последние строки лога.
    Полный статус — от имени администратора; без прав виден только лог.
#>
param(
    [string]$InstallDir = $(if (Test-Path 'D:\') { 'D:\TaskReminderBot' } else { 'C:\TaskReminderBot' }),
    [string]$TaskName = 'TaskReminderBot',
    [int]$Lines = 30
)

. "$PSScriptRoot\common.ps1"

# Бот работает от SYSTEM: без прав администратора не видно ни задачу, ни командную строку его процесса
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'Без прав администратора задача и процесс бота не видны — показываю только лог.' -ForegroundColor Yellow
    Write-Host 'Для полного статуса запустите PowerShell от имени администратора.' -ForegroundColor Yellow
    $log = Join-Path $InstallDir 'logs\bot.log'
    if (Test-Path $log) {
        $last = Select-String -Path $log -Pattern 'Bot started' -Encoding UTF8 | Select-Object -Last 1
        Write-Host ("Лог изменён: {0}; последнее событие запуска: {1}" -f (Get-Item $log).LastWriteTime, $(if ($last) { $last.Line.Trim() } else { 'нет' }))
    }
    Write-Host ''
    Show-LogTail $InstallDir $Lines
    return
}

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
