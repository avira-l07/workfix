$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

function Push-PackFile {
    param(
        [string]$SourcePath,
        [string]$DestSubPath
    )
    $fileName = [System.IO.Path]::GetFileName($SourcePath)
    Write-Host "Transferring $fileName -> $DestSubPath..."
    
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
    $tmpMarker = "/data/local/tmp/marker.tmp"
    # Write content to local file and push
    $localTmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($localTmp, $Content, [System.Text.Encoding]::ASCII)
    & $adb push $localTmp $tmpMarker
    Remove-Item $localTmp
    & $adb shell "chmod 777 $tmpMarker"
    & $adb shell "run-as com.example.itantra cp $tmpMarker files/language_packs/$MarkerSubPath"
    & $adb shell "rm -f $tmpMarker"
    Write-Host "  -> Set marker $MarkerSubPath"
}

Write-Host "=== 1. Transferring Shared Whisper STT ==="
Push-PackFile "app/src/main/assets/language_packs/shared/stt/tiny-encoder.int8.onnx" "shared/stt/tiny-encoder.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt/tiny-decoder.int8.onnx" "shared/stt/tiny-decoder.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt/tiny-tokens.txt" "shared/stt/tiny-tokens.txt"
Set-PackMarker "shared/stt/.verified_v1" "1.0.0`n"

Write-Host "=== 2. Transferring Hindi CTC STT ==="
Push-PackFile "app/src/main/assets/language_packs/shared/stt-hi-ctc-v2/model.int8.onnx" "shared/stt-hi-ctc-v2/model.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt-hi-ctc-v2/tokens.txt" "shared/stt-hi-ctc-v2/tokens.txt"
$hiSig = "model.int8.onnx:197595593:915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa`ntokens.txt:67605:ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
Set-PackMarker "shared/stt-hi-ctc-v2/.verified_hi_ctc_v2" $hiSig

Write-Host "=== 3. Transferring English CTC STT ==="
Push-PackFile "app/src/main/assets/language_packs/shared/stt-en-ctc-v2/model.int8.onnx" "shared/stt-en-ctc-v2/model.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt-en-ctc-v2/tokens.txt" "shared/stt-en-ctc-v2/tokens.txt"
$enSig = "model.int8.onnx:174610057:28b9261a53028a7c99ff0799f44fb53f19c78b68cc4cf40637ac9c16cb1fbc6f`ntokens.txt:11433:89c165b98df7af718ec0e872177279bfad4ade51331f1be92753c9583a1ef30d"
Set-PackMarker "shared/stt-en-ctc-v2/.verified_en_ctc_v2" $enSig

Write-Host "=== 4. Transferring Gujarati CTC STT ==="
Push-PackFile "tools/stt_models/indicconformer-candidates/gu/model.int8.onnx" "shared/stt-gu-ctc-v2/model.int8.onnx"
Push-PackFile "app/src/main/assets/language_packs/shared/stt-hi-ctc-v2/tokens.txt" "shared/stt-gu-ctc-v2/tokens.txt"
$guSig = "model.int8.onnx:197595461:822ed7f0b809bbd479275bf91c913d05564b88c0d082bbcba2f37999b88cb598`ntokens.txt:67605:ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
Set-PackMarker "shared/stt-gu-ctc-v2/.verified_gu_ctc_v2" $guSig

Write-Host "=== 5. Transferring Hindi TTS ==="
Push-PackFile "app/src/main/assets/language_packs/hi/tts/model.onnx" "hi/tts/model.onnx"
Push-PackFile "app/src/main/assets/language_packs/hi/tts/tokens.txt" "hi/tts/tokens.txt"

Write-Host "=== 6. Transferring English TTS ==="
Push-PackFile "app/src/main/assets/language_packs/en/tts/model.onnx" "en/tts/model.onnx"
Push-PackFile "app/src/main/assets/language_packs/en/tts/tokens.txt" "en/tts/tokens.txt"

Write-Host "=== 7. Transferring Gujarati TTS ==="
Push-PackFile "models/bundled/gu/tts/model.onnx" "gu/tts/model.onnx"
Push-PackFile "app/src/main/assets/language_packs/gu/tts/tokens.txt" "gu/tts/tokens.txt"

Write-Host "=== All language packs transferred successfully! ==="
