<#
.SYNOPSIS
    Устанавливает бота как фоновую задачу Windows: запуск при включении компьютера (до входа в систему),
    автоперезапуск при падении, лог в файл.

.EXAMPLE
    # PowerShell от имени администратора, из папки проекта:
    powershell -ExecutionPolicy Bypass -File deploy\windows\install.ps1
#>
param(
    # Куда установить (без пробелов и кириллицы в пути — так надёжнее). По умолчанию диск D, если он есть
    [string]$InstallDir = $(if (Test-Path 'D:\') { 'D:\TaskReminderBot' } else { 'C:\TaskReminderBot' }),
    # Папка JDK/JRE 21+; по умолчанию ищется автоматически
    [string]$JavaHome,
    [string]$TaskName = 'TaskReminderBot'
)

. "$PSScriptRoot\common.ps1"
Assert-Admin

Write-Host "Установка бота в $InstallDir" -ForegroundColor Cyan

# 1. Что ставим
$jar = Get-BuiltJar
$javaExe = Resolve-JavaExe $JavaHome
$sourceConfig = Join-Path $RepoRoot 'config.properties'
$targetConfig = Join-Path $InstallDir 'config.properties'
if (-not (Test-Path $targetConfig) -and -not (Test-Path $sourceConfig)) {
    throw "Нет config.properties ни в $InstallDir, ни в $RepoRoot. Скопируйте config.example.properties в config.properties и заполните."
}
Write-Host "  jar:  $($jar.FullName)"
Write-Host "  java: $javaExe"

# 2. Если уже установлено — остановить перед заменой файлов
Stop-Bot $TaskName $InstallDir

# 3. Файлы
New-Item -ItemType Directory -Force (Join-Path $InstallDir 'logs') | Out-Null
Copy-Item $jar.FullName (Join-Path $InstallDir $JarName) -Force
Copy-Item (Join-Path $PSScriptRoot 'run.cmd') (Join-Path $InstallDir 'run.cmd') -Force
Set-Content (Join-Path $InstallDir 'env.cmd') "@set `"JAVA_EXE=$javaExe`"" -Encoding ASCII

if (Test-Path $targetConfig) {
    Write-Host "  config.properties уже есть в $InstallDir — оставляю как есть"
} else {
    Copy-Item $sourceConfig $targetConfig
    Write-Host "  config.properties скопирован из проекта"
}
# Ссылки на артефакты (если включены) хранятся рядом с ботом; перенести существующую базу из проекта
$sourceDb = Join-Path $RepoRoot 'artifacts.db'
$targetDb = Join-Path $InstallDir 'artifacts.db'
if ((Test-Path $sourceDb) -and -not (Test-Path $targetDb)) {
    Copy-Item $sourceDb $targetDb
    Write-Host '  artifacts.db скопирована из проекта'
}

# 4. Токен в конфиге: доступ только системе, администраторам и текущему пользователю
& icacls $targetConfig /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' "$($env:USERDOMAIN)\$($env:USERNAME):M" | Out-Null

# 5. Задача планировщика: при старте системы, от SYSTEM, без ограничения по времени, перезапуск при сбое
$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$(Join-Path $InstallDir 'run.cmd')`"" -WorkingDirectory $InstallDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Telegram-бот напоминаний команды (task-reminder-bot)' -Force | Out-Null
Write-Host "  задача планировщика '$TaskName' зарегистрирована"

# 6. Запуск и проверка
$startedAt = Get-Date
Start-ScheduledTask -TaskName $TaskName
Write-Host 'Запускаю и жду старта бота (до 90 сек)...'
if (Wait-BotStarted $InstallDir $startedAt) {
    Write-Host 'Бот запущен.' -ForegroundColor Green
} else {
    Write-Host 'Бот не подтвердил запуск — смотрите лог ниже. Задача продолжит перезапускать его каждые 15 сек.' -ForegroundColor Red
}
Show-LogTail $InstallDir 15
Write-OtherInstancesWarning

Write-Host ''
Write-Host 'Чтобы бот работал круглосуточно, компьютер не должен засыпать (см. deploy\windows\README.md).' -ForegroundColor Cyan
Write-Host "Статус: deploy\windows\status.ps1   Обновление: deploy\windows\update.ps1 -Build   Лог: $InstallDir\logs\bot.log"
