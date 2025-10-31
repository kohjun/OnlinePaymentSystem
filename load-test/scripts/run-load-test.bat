@echo off
REM ============================================
REM Payment Service Performance Test (Windows)
REM ============================================

echo ============================================
echo   JMeter Performance Test Runner
echo ============================================
echo.

REM 1. JMeter 설치 확인
echo [1/4] Checking JMeter installation...
where jmeter >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] JMeter not found!
    echo Please add JMeter bin folder to PATH
    echo Example: C:\apache-jmeter-5.6.2\bin
    pause
    exit /b 1
)
echo [OK] JMeter found
jmeter --version | findstr "jmeter"
echo.

REM 2. 애플리케이션 상태 확인
echo [2/4] Checking application status...
curl -s -f http://localhost:8080/api/system/health >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Application is not running!
    echo Please start your application first:
    echo   gradlew.bat bootRun --args="--spring.profiles.active=dev"
    pause
    exit /b 1
)
echo [OK] Application is running
echo.

REM 3. 결과 디렉토리 생성
echo [3/4] Creating result directory...
set TIMESTAMP=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set TIMESTAMP=%TIMESTAMP: =0%
set RESULT_DIR=load-test\results\%TIMESTAMP%
mkdir %RESULT_DIR% 2>nul
echo [OK] Result directory: %RESULT_DIR%
echo.

REM 4. JMeter 테스트 실행
echo [4/4] Running performance test...
echo.
echo ================================================
jmeter -n ^
    -t load-test\jmeter\payment-service-test.jmx ^
    -l %RESULT_DIR%\results.jtl ^
    -e -o %RESULT_DIR%\html-report ^
    -JTHREADS=50 ^
    -JRAMPUP=30 ^
    -JDURATION=180

echo ================================================
echo.
echo [SUCCESS] Test completed!
echo.
echo Results:
echo   - JTL file: %RESULT_DIR%\results.jtl
echo   - HTML report: %RESULT_DIR%\html-report\index.html
echo.
echo Open report:
echo   start %RESULT_DIR%\html-report\index.html
echo.
pause