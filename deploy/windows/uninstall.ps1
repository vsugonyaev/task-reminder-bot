<#
.SYNOPSIS
    Останавливает бота и удаляет задачу планировщика. Файлы в папке установки (конфиг с токеном,
    база артефактов, логи) по умолчанию остаются; -RemoveFiles удаляет и их.
#>
param(
    [string]$InstallDir = $(if (Test-Path 'D:\') { 'D:\TaskReminderBot' } else { 'C:\TaskReminderBot' }),
    [string]$TaskName = 'TaskReminderBot',
    [switch]$RemoveFiles
)

. "$PSScriptRoot\common.ps1"
Assert-Admin

Stop-Bot $TaskName $InstallDir
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Задача '$TaskName' удалена."
}

if ($RemoveFiles) {
    if (Test-Path $InstallDir) {
        Remove-Item $InstallDir -Recurse -Force
        Write-Host "Папка $InstallDir удалена."
    }
} else {
    Write-Host "Файлы оставлены в $InstallDir (config.properties, artifacts.db, logs). Удалить: -RemoveFiles"
}
