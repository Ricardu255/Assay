@echo off
setlocal
pushd "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java 17 or newer is required. Install a JDK, then open this launcher again.
  if "%~1"=="" pause
  popd
  exit /b 1
)
set "ASSAY_JAR=assay.jar"
if not exist "%ASSAY_JAR%" (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo This source checkout needs Maven. Add Maven to PATH, then try again.
    if "%~1"=="" pause
    popd
    exit /b 1
  )
  echo Building Assay...
  call mvn -q -DskipTests package
  if errorlevel 1 (
    if "%~1"=="" pause
    popd
    exit /b 1
  )
  set "ASSAY_JAR=app\target\assay.jar"
)
if not "%~1"=="" goto run
java -jar "%ASSAY_JAR%" demo
set "ASSAY_EXIT=%errorlevel%"
popd
exit /b %ASSAY_EXIT%

:run
java -jar "%ASSAY_JAR%" %*
set "ASSAY_EXIT=%errorlevel%"
popd
exit /b %ASSAY_EXIT%
