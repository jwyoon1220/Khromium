$ErrorActionPreference = "Stop"

$CPP_DIR = "$PSScriptRoot\src\main\cpp"
$BUILD_DIR = "$PSScriptRoot\build\native"

# 1. 빌드 디렉토리 생성
if (-not (Test-Path $BUILD_DIR)) {
    New-Item -ItemType Directory -Force -Path $BUILD_DIR | Out-Null
}

# 2. 의존성 라이브러리 체크 및 복제
if (-not (Test-Path "$BUILD_DIR\tlsf")) {
    Write-Host "Cloning TLSF..." -ForegroundColor Cyan
    git clone https://github.com/mattconte/tlsf.git "$BUILD_DIR\tlsf"
}

if (-not (Test-Path "$BUILD_DIR\quickjs")) {
    Write-Host "Cloning QuickJS..." -ForegroundColor Cyan
    git clone https://github.com/bellard/quickjs.git "$BUILD_DIR\quickjs"
    
    # Windows/MSVC 환경에서 pthread 누락으로 인한 빌드 실패 방지 패치
    $qjsc = "$BUILD_DIR\quickjs\quickjs.c"
    if (Test-Path $qjsc) {
        Write-Host "Patching quickjs.c for Windows compatibility..." -ForegroundColor Yellow
        $content = Get-Content $qjsc -Raw
        $content = $content -replace '#define CONFIG_ATOMICS', '/* #define CONFIG_ATOMICS */'
        Set-Content $qjsc $content -NoNewline
    }
}

# 3. JAVA_HOME 확인
$V_JAVA_HOME = $env:JAVA_HOME
if (-not $V_JAVA_HOME) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $V_JAVA_HOME = (Get-Item $javac.Path).Directory.Parent.FullName }
}

if (-not $V_JAVA_HOME) {
    Write-Error "JAVA_HOME is not set and could not be guessed."
    exit 1
}

$JNI_INC = "$V_JAVA_HOME\include"
$JNI_WIN_INC = "$V_JAVA_HOME\include\win32"
$Q_JNI_INC = "`"$JNI_INC`""
$Q_JNI_WIN_INC = "`"$JNI_WIN_INC`""

Write-Host "Using JNI Includes: $JNI_INC" -ForegroundColor Green

# 4. 컴파일러 설정 및 소스 파일 수집
$CLANG_CMD = "clang++"
$CPP_FILES = Get-ChildItem -Path $CPP_DIR -Filter "*.cpp" | Select-Object -ExpandProperty FullName

# 5. 컴파일 인자 구성
# 주의: CONFIG_VERSION의 따옴표 처리를 위해 ' '와 " "를 조합합니다.
$CLANG_ARGS = @(
    "-shared",
    "-o", "$BUILD_DIR\KhromiumCore.dll",
    "-I$Q_JNI_INC",
    "-I$Q_JNI_WIN_INC",
    "-I", "$BUILD_DIR\tlsf",
    "-I", "$BUILD_DIR\quickjs",
    "-I", "$CPP_DIR",
    "-D_GNU_SOURCE",
    "-DCONFIG_WIN32",
    "-DWIN32",
    "-D_CRT_SECURE_NO_WARNINGS",
    "-D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH"
)

# 6. 소스 파일 추가 (언어 모드 명시)
# C++ 프로젝트 파일들
$CLANG_ARGS += "-x", "c++"
$CLANG_ARGS += $CPP_FILES

# C 라이브러리 파일들 (TLSF & QuickJS)
$CLANG_ARGS += "-x", "c"
$CLANG_ARGS += "$BUILD_DIR\tlsf\tlsf.c"
$CLANG_ARGS += "$BUILD_DIR\quickjs\quickjs.c"
$CLANG_ARGS += "$BUILD_DIR\quickjs\libunicode.c"
$CLANG_ARGS += "$BUILD_DIR\quickjs\libregexp.c"
$CLANG_ARGS += "$BUILD_DIR\quickjs\cutils.c"
$CLANG_ARGS += "$BUILD_DIR\quickjs\dtoa.c"

# 7. 런타임 라이브러리 설정
$CLANG_ARGS += "-rtlib=compiler-rt"

Write-Host "Compiling native DLL..." -ForegroundColor Cyan
$process = Start-Process -NoNewWindow -Wait -FilePath $CLANG_CMD -ArgumentList $CLANG_ARGS -PassThru -RedirectStandardError "$BUILD_DIR\clang.err" -RedirectStandardOutput "$BUILD_DIR\clang.out"
if ($process.ExitCode -ne 0) {
    Write-Error "Clang compilation failed with exit code ($process.ExitCode). See clang.err for details."
    exit 1
}

Write-Host "--------------------------------------------" -ForegroundColor Green
Write-Host "Build successfully finished!" -ForegroundColor Green
Write-Host "DLL location: $BUILD_DIR\KhromiumCore.dll" -ForegroundColor Green