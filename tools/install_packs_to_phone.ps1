$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    throw "adb.exe not found at $adb. Please verify Android SDK is installed."
}

Write-Host "Checking connected Android device..."
$devices = & $adb devices | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Host "`n[!] No authorized Android device detected by adb." -ForegroundColor Yellow
    Write-Host "Please ensure:" -ForegroundColor Yellow
    Write-Host "  1. Your phone is connected via USB." -ForegroundColor Yellow
    Write-Host "  2. 'USB Debugging' is enabled in Settings -> Developer options." -ForegroundColor Yellow
    Write-Host "  3. When prompted on your phone screen, tap 'Allow USB debugging'." -ForegroundColor Yellow
    Write-Host "`nRun '& ""$adb"" devices' to check connection.`n" -ForegroundColor Yellow
    exit 1
}

# Ensure app is installed
Write-Host "Verifying app package 'com.example.itantra' on device..."
$pkgCheck = & $adb shell "pm path com.example.itantra"
if (-not ($pkgCheck -match "package:")) {
    Write-Host "App is not installed yet. Installing debug APK..." -ForegroundColor Cyan
    $apkPath = "app/build/outputs/apk/debug/app-debug.apk"
    if (Test-Path $apkPath) {
        & $adb install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw "Failed to install $apkPath" }
    } else {
        throw "Debug APK not found at $apkPath. Please build the project first."
    }
}

function Push-PackFile {
    param(
        [string]$SourcePath,
        [string]$DestSubPath
    )
    if (-not (Test-Path $SourcePath)) {
        throw "Source file missing: $SourcePath"
    }

    $fileName = [System.IO.Path]::GetFileName($SourcePath)
    Write-Host "Transferring $fileName -> $DestSubPath..."
    
    # Ensure destination directory exists on device
    $destDir = [System.IO.Path]::GetDirectoryName($DestSubPath).Replace('\', '/')
    if ($destDir) {
        & $adb shell "run-as com.example.itantra mkdir -p files/language_packs/$destDir"
    }

    # Push to temp location
    $tmpPath = "/data/local/tmp/$fileName"
    & $adb push $SourcePath $tmpPath
    if ($LASTEXITCODE -ne 0) { throw "Failed to push $SourcePath" }
    
    # Make accessible to run-as
    & $adb shell "chmod 777 $tmpPath"
    
    # Copy to app directory
    & $adb shell "run-as com.example.itantra cp $tmpPath files/language_packs/$DestSubPath"
    if ($LASTEXITCODE -ne 0) { throw "Failed to copy to files/language_packs/$DestSubPath" }
    
    # Clean up temp file
    & $adb shell "rm -f $tmpPath"
    Write-Host "  -> Done $fileName"
}

function Set-PackMarker {
    param(
        [string]$MarkerSubPath,
        [string]$Content
    )
    $destDir = [System.IO.Path]::GetDirectoryName($MarkerSubPath).Replace('\', '/')
    if ($destDir) {
        & $adb shell "run-as com.example.itantra mkdir -p files/language_packs/$destDir"
    }

    $tmpMarker = "/data/local/tmp/marker.tmp"
    $localTmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($localTmp, $Content, [System.Text.Encoding]::ASCII)
    & $adb push $localTmp $tmpMarker
    Remove-Item $localTmp
    & $adb shell "chmod 777 $tmpMarker"
    & $adb shell "run-as com.example.itantra cp $tmpMarker files/language_packs/$MarkerSubPath"
    & $adb shell "rm -f $tmpMarker"
    Write-Host "  -> Set marker $MarkerSubPath"
}

Write-Host "`n=== 1. Transferring Shared Whisper STT ===" -ForegroundColor Green
Push-PackFile "app/src/main/assets/language_packs/shared/stt/tiny-encoder.int8.onnx" "shared/stt/tiny-encoder.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt/tiny-decoder.int8.onnx" "shared/stt/tiny-decoder.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt/tiny-tokens.txt" "shared/stt/tiny-tokens.txt"
Set-PackMarker "shared/stt/.verified_v1" "1.0.0`n"

Write-Host "`n=== 2. Transferring Hindi CTC STT ===" -ForegroundColor Green
Push-PackFile "app/src/main/assets/language_packs/shared/stt-hi-ctc-v2/model.int8.onnx" "shared/stt-hi-ctc-v2/model.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt-hi-ctc-v2/tokens.txt" "shared/stt-hi-ctc-v2/tokens.txt"
$hiSig = "model.int8.onnx:197595593:915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa`ntokens.txt:67605:ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
Set-PackMarker "shared/stt-hi-ctc-v2/.verified_hi_ctc_v2" $hiSig

Write-Host "`n=== 3. Transferring English CTC STT ===" -ForegroundColor Green
Push-PackFile "app/src/main/assets/language_packs/shared/stt-en-ctc-v2/model.int8.onnx" "shared/stt-en-ctc-v2/model.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt-en-ctc-v2/tokens.txt" "shared/stt-en-ctc-v2/tokens.txt"
$enSig = "model.int8.onnx:174610057:28b9261a53028a7c99ff0799f44fb53f19c78b68cc4cf40637ac9c16cb1fbc6f`ntokens.txt:11433:89c165b98df7af718ec0e872177279bfad4ade51331f1be92753c9583a1ef30d"
Set-PackMarker "shared/stt-en-ctc-v2/.verified_en_ctc_v2" $enSig

Write-Host "`n=== 4. Transferring Gujarati CTC STT ===" -ForegroundColor Green
Push-PackFile "tools/stt_models/indicconformer-candidates/gu/model.int8.onnx" "shared/stt-gu-ctc-v2/model.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt-hi-ctc-v2/tokens.txt" "shared/stt-gu-ctc-v2/tokens.txt"
$guSig = "model.int8.onnx:197595461:822ed7f0b809bbd479275bf91c913d05564b88c0d082bbcba2f37999b88cb598`ntokens.txt:67605:ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
Set-PackMarker "shared/stt-gu-ctc-v2/.verified_gu_ctc_v2" $guSig

Write-Host "`n=== 5. Transferring Hindi TTS ===" -ForegroundColor Green
Push-PackFile "app/src/main/assets/language_packs/hi/tts/model.onnx" "hi/tts/model.onnx"
Push-PackFile "app/src/main/assets/language_packs/hi/tts/tokens.txt" "hi/tts/tokens.txt"
Set-PackMarker "hi/tts/.verified_v1" "2.0.0`n"

Write-Host "`n=== 6. Transferring English TTS ===" -ForegroundColor Green
Push-PackFile "app/src/main/assets/language_packs/en/tts/model.onnx" "en/tts/model.onnx"
Push-PackFile "app/src/main/assets/language_packs/en/tts/tokens.txt" "en/tts/tokens.txt"
Set-PackMarker "en/tts/.verified_v1" "2.0.0`n"

Write-Host "`n=== 7. Transferring Gujarati TTS ===" -ForegroundColor Green
Push-PackFile "models/bundled/gu/tts/model.onnx" "gu/tts/model.onnx"
Push-PackFile "app/src/main/assets/language_packs/gu/tts/tokens.txt" "gu/tts/tokens.txt"
Set-PackMarker "gu/tts/.verified_v1" "2.0.0`n"

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "=== All Hindi, English & Gujarati packs transferred! ===" -ForegroundColor Green
Write-Host "========================================================`n" -ForegroundColor Green
