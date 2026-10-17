@echo off
setlocal
pushd "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java 17 or newer is required. Install a JDK, then open this launcher again.
  popd
  exit /b 1
)
set "ASSAY_JAR=assay.jar"
if not exist "%ASSAY_JAR%" (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo This source checkout needs Maven. Add Maven to PATH, then try again.
    popd
    exit /b 1
  )
  echo Building Assay...
  call mvn -q -DskipTests package
  if errorlevel 1 (
    popd
    exit /b 1
  )
  set "ASSAY_JAR=target\assay.jar"
)
if "%~1"=="" goto demo
if /i "%~1"=="demo" goto demo
java -jar "%ASSAY_JAR%" %*
set "ASSAY_EXIT=%errorlevel%"
popd
exit /b %ASSAY_EXIT%
:demo
echo Running the bundled example. No API key is needed.
java -jar "%ASSAY_JAR%" run --adapter io.assay.examples.EchoAdapter --suite smoke --cases examples/cases.example.jsonl
if errorlevel 1 (
  pause
  popd
  exit /b 1
)
echo Opening the dashboard. Keep this window open; press Ctrl+C to stop.
java -jar "%ASSAY_JAR%" serve --open-browser
set "ASSAY_EXIT=%errorlevel%"
popd
exit /b %ASSAY_EXIT%
