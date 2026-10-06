$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="source_lang">hi</string>
    <set name="enabled_mic_langs">
        <string>hi</string>
        <string>en</string>
        <string>gu</string>
    </set>
    <set name="enabled_listen_langs">
        <string>hi</string>
        <string>en</string>
        <string>gu</string>
    </set>
</map>
"@

$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $xml, [System.Text.Encoding]::UTF8)

& $adb push $tmp /data/local/tmp/lang_prefs.xml
Remove-Item $tmp

& $adb shell "chmod 777 /data/local/tmp/lang_prefs.xml"
& $adb shell "run-as com.example.itantra cp /data/local/tmp/lang_prefs.xml shared_prefs/lang_prefs.xml"
& $adb shell "rm /data/local/tmp/lang_prefs.xml"
Write-Host "Updated lang_prefs.xml successfully"
