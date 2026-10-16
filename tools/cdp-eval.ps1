# Run JS in an emulator Chrome tab via DevTools and print the result (investigation helper).
# Usage: powershell -File cdp-eval.ps1 -UrlLike '*namu.wiki*' -JsFile probe.js
param([string]$UrlLike, [string]$JsFile)
$js = [IO.File]::ReadAllText($JsFile, [Text.Encoding]::UTF8)
# Prefer the tab that is actually visible (background tabs may be frozen)
$tabs = (Invoke-RestMethod http://127.0.0.1:9222/json -TimeoutSec 10) | Where-Object { $_.type -eq 'page' -and $_.url -like $UrlLike }
$t = $tabs | Where-Object { $_.description -notmatch 'hidden' } | Select-Object -First 1
if (-not $t) { "no tab"; exit 1 }
$js = "(document.visibilityState === 'visible' ? '' : '[hidden tab] ') + (" + $js + ")"
$cts = New-Object Threading.CancellationTokenSource 20000
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.ConnectAsync([Uri]$t.webSocketDebuggerUrl, $cts.Token).Wait()
$msg = @{ id = 1; method = 'Runtime.evaluate'; params = @{ expression = $js; returnByValue = $true } } | ConvertTo-Json -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($msg)
$ws.SendAsync([ArraySegment[byte]]$bytes, 'Text', $true, $cts.Token).Wait()
$buf = New-Object byte[] 1048576
while ($true) {
    $sb = New-Object Text.StringBuilder
    do { $r = $ws.ReceiveAsync([ArraySegment[byte]]$buf, $cts.Token).Result; [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $r.Count)) } while (-not $r.EndOfMessage)
    $o = $sb.ToString() | ConvertFrom-Json
    if ($o.id -eq 1) {
        if ($o.result.exceptionDetails) { "ERR: " + $o.result.exceptionDetails.exception.description } else { [string]$o.result.result.value }
        break
    }
}
