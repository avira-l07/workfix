param([ValidateSet('Windows','Android')][string]$Target = 'Windows')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'stt_models/sherpa-source'
$patch = Join-Path $PSScriptRoot 'sherpa-whisper-repair.patch'
if (!(Test-Path "$source/CMakeLists.txt")) {
    git clone --depth 1 --branch v1.13.8 https://github.com/k2-fsa/sherpa-onnx.git $source
    if ($LASTEXITCODE) { throw 'Source download failed' }
}
$revision = git -C $source rev-parse HEAD
if ($revision -ne '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf') { throw 'Unexpected Sherpa source revision' }
git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Patch does not match source' }
    git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Could not apply patch' }
}
$common = @('-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF', '-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF',
    '-DSHERPA_ONNX_ENABLE_BINARY=OFF', '-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF',
    '-DSHERPA_ONNX_ENABLE_C_API=OFF', '-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON')
if ($Target -eq 'Windows') {
    $vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
    $vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (!$vs) { throw 'Visual Studio C++ Build Tools are required' }
    $cmake = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
    $build = Join-Path $PSScriptRoot 'stt_models/build-windows'
    & $cmake -S $source -B $build -G 'Visual Studio 18 2026' -A x64 -DSHERPA_ONNX_ENABLE_PYTHON=ON @common
    if ($LASTEXITCODE) { throw 'Windows configure failed' }
    & $cmake --build $build --config Release --target _sherpa_onnx --parallel 4
} else {
    $sdk = $env:ANDROID_HOME
    if (!$sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $cmake = Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
    $ninja = Join-Path $sdk 'cmake/3.22.1/bin/ninja.exe'
    $toolchain = Join-Path $sdk 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
    $build = Join-Path $PSScriptRoot 'stt_models/build-android'
    & $cmake -S $source -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release `
        -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_JNI=ON @common
    if ($LASTEXITCODE) { throw 'Android configure failed' }
    & $cmake --build $build --target sherpa-onnx-jni --parallel 4
}
if ($LASTEXITCODE) { throw 'Native build failed' }
