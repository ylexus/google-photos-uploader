cd /D "%~dp0"
if [%1]==[] goto error
if not exist build mkdir build
set LOG="pull_and_build.log"
del %LOG%
git fetch --all >>%LOG% 2>&1
git reset --hard origin/master >>%LOG% 2>&1
echo Version=%1 >>%LOG%
gradlew --info clean fullPackage -DVERSION=%1 -DCLIENT_SECRET_PATH=c:\java\clientSecret.json >>%LOG% 2>&1
goto eof

:error
echo "Missing version argument"
exit /b 1

:eof