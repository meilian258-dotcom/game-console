param([switch]$ValidateOnly, [switch]$Cancel, [ValidateRange(15,120)][int]$DelaySeconds=60)
$ErrorActionPreference='Stop'
$fcDelivery='G:\服务器\服务器Codex\制作Mod\03-街机模拟\PIQ-FC街机'
$fcCancel=Join-Path $PSScriptRoot 'cancel-fc-alpha6-shutdown.flag'
if ($Cancel) { New-Item -ItemType File -Path $fcCancel -Force | Out-Null; Write-Output 'FC alpha6 shutdown cancelled.'; exit 0 }
$fcRequired=@{
 'piq_fc_arcade-0.31.0-alpha.6.jar'='BF4DF8ECC2F7D4F542ADB3843CAE35AB446B11809A8DD3AF35FAB50906D0FE78'
 '家用FC-0.31.0-alpha.6-模型预览/final-jar-validation.json'='B4A9B22AAE768F3ADD2D1CA52AAB07FC3147F1BBF3657BEDBC98C7025ADB25C7'
 '家用FC-0.31.0-alpha.6-使用说明.md'='52628C2D556764362013FD342F332255F5020D6AFFDD7EA83F348AD40A32E0EA'
 '家用FC-0.31.0-alpha.6-校验清单.md'='2AE058B1D25F31188E5BAE533E838B9BEBC3B7FBB39BAC07D4B2886231D8FBF4'
 '小霸王SB926两格宽开合槽-v3/wide-runtime-render-audit.json'='3C587310F78E66B6407333DFAC669552EFB74B5B1D8E62D5BAC0A1CB76CC0221'
 '双人街机与薄LCD-alpha6/双人街机实际模型预览/运行时管线QA/runtime-render-audit.json'='964A505F5FBBA6556F4373820D974FD9A9A2C74D20976880FAF7274FA3B1FDE7'
}
function Confirm-FcAlpha6Delivery {
 foreach ($fcName in $fcRequired.Keys) {
  $fcPath=Join-Path $fcDelivery $fcName
  if (!(Test-Path -LiteralPath $fcPath -PathType Leaf) -or (Get-FileHash -LiteralPath $fcPath -Algorithm SHA256).Hash -ne $fcRequired[$fcName]) {
   throw "FC alpha6 artifact missing/changed; shutdown cancelled: $fcName"
  }
 }
}
Confirm-FcAlpha6Delivery
if ($ValidateOnly) { Write-Output 'All six final alpha6 artifacts verified; no shutdown requested.'; exit 0 }
if (Test-Path -LiteralPath $fcCancel) { Write-Output 'Cancellation marker exists; no shutdown requested.'; exit 0 }
Start-Sleep -Seconds $DelaySeconds
if (Test-Path -LiteralPath $fcCancel) { Write-Output 'Cancelled during grace period.'; exit 0 }
Confirm-FcAlpha6Delivery
# Delay here, not shutdown /t 60 (which would imply forced application closure).
& "$env:SystemRoot\System32\shutdown.exe" /s /t 0 /d p:0:0 /c 'PIQ FC alpha6 delivery verified; shutdown explicitly requested by user.'
if ($LASTEXITCODE -ne 0) { throw "Windows rejected shutdown: $LASTEXITCODE" }
