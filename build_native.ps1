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
    Write-Host "Cloning QuickJS-NG..." -ForegroundColor Cyan
    git clone https://github.com/quickjs-ng/quickjs.git "$BUILD_DIR\quickjs"
}

# 3. JAVA_HOME 확인 및 JNI 경로 설정
$V_JAVA_HOME = $env:JAVA_HOME
if (-not $V_JAVA_HOME) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $V_JAVA_HOME = (Get-Item $javac.Path).Directory.Parent.FullName }
}

# 공백 대응을 위해 경로를 인용부호로 감쌉니다.
$JNI_INC = "`"$V_JAVA_HOME\include`""
$JNI_WIN_INC = "`"$V_JAVA_HOME\include\win32`""

Write-Host "Using JNI Includes: $V_JAVA_HOME\include" -ForegroundColor Green

# 4. 컴파일러 설정 및 소스 파일 수집
$CLANG_CMD = "clang++"
$CPP_FILES = Get-ChildItem -Path $CPP_DIR -Filter "*.cpp" | Select-Object -ExpandProperty FullName

# 5. 컴파일 인자 구성
# 인자에 공백이 포함될 경우를 대비해 탭이나 공백 처리를 Clang 인자 형식에 맞게 조정
$CLANG_ARGS = @(
    "-shared",
    "-o", "`"$BUILD_DIR\KhromiumCore.dll`"",
    "-I$JNI_INC",
    "-I$JNI_WIN_INC",
    "-I`"$BUILD_DIR\tlsf`"",
    "-I`"$BUILD_DIR\quickjs`"",
    "-I`"$CPP_DIR`"",
    "-D_GNU_SOURCE",
    "-DCONFIG_WIN32",
    "-DWIN32",
    "-D_CRT_SECURE_NO_WARNINGS",
    "-D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH",
    "-fms-extensions",
    "-m64"
)

# 6. 언어 모드 및 소스 파일 추가
$CLANG_ARGS += "-x", "c++"
foreach($file in $CPP_FILES) { $CLANG_ARGS += "`"$file`"" }

$CLANG_ARGS += "-x", "c"
$CLANG_ARGS += "`"$BUILD_DIR\tlsf\tlsf.c`""
$CLANG_ARGS += "`"$BUILD_DIR\quickjs\quickjs.c`""
$CLANG_ARGS += "`"$BUILD_DIR\quickjs\libunicode.c`""
$CLANG_ARGS += "`"$BUILD_DIR\quickjs\libregexp.c`""
$CLANG_ARGS += "`"$BUILD_DIR\quickjs\cutils.c`""
# dtoa.c was merged into quickjs.c in quickjs-ng; only add it if it exists
if (Test-Path "$BUILD_DIR\quickjs\dtoa.c") {
    $CLANG_ARGS += "`"$BUILD_DIR\quickjs\dtoa.c`""
}

# 7. 런타임 설정
$CLANG_ARGS += "-rtlib=compiler-rt"

Write-Host "Compiling native DLL..." -ForegroundColor Cyan

# 로그 파일 초기화
"" | Out-File -FilePath "$BUILD_DIR\clang.err"

# Start-Process 대신 호출 연산자(&)를 사용하여 인자 전달 문제를 회피합니다.
$argString = $CLANG_ARGS -join " "
try {
    # 직접 문자열로 인자를 전달하여 공백 문제를 해결
    cmd /c "$CLANG_CMD $argString 2> `"$BUILD_DIR\clang.err`" 1> `"$BUILD_DIR\clang.out`""

    if ($LASTEXITCODE -ne 0) {
        throw "Exit code $LASTEXITCODE"
    }
} catch {
    $errorMessage = Get-Content "$BUILD_DIR\clang.err" -Raw
    Write-Host "--------------------------------------------" -ForegroundColor Red
    Write-Host "Clang compilation failed." -ForegroundColor Red
    Write-Host "Error Details:`n$errorMessage" -ForegroundColor Yellow
    exit 1
}

Write-Host "--------------------------------------------" -ForegroundColor Green
Write-Host "Build successfully finished!" -ForegroundColor Green
Write-Host "DLL location: $BUILD_DIR\KhromiumCore.dll" -ForegroundColor Green