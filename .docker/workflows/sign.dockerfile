FROM mcr.microsoft.com/windows/servercore:ltsc2022

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1

COPY build-image.ps1 C:/scripts/build-image.ps1
RUN C:/scripts/build-image.ps1

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress git; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install git failed' }; \
    git --version

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress corretto11jdk; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install corretto11jdk failed' }; \
    $$corretto = (Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName; \
    if (-not $$corretto) { throw 'Corretto 11 not found after choco install (corretto11jdk).' }; \
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $$corretto, 'Machine'); \
    [Environment]::SetEnvironmentVariable('JDK11_HOME', $$corretto, 'Machine'); \
    & (Join-Path $$corretto 'bin\java.exe') -version

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress maven; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install maven failed' }; \
    $$mavenHome = Get-ChildItem 'C:\ProgramData\chocolatey\lib\maven\tools' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1; \
    if ($$mavenHome) { [Environment]::SetEnvironmentVariable('MAVEN_HOME', $$mavenHome.FullName, 'Machine') }; \
    mvn --version

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress dotnet-8.0-sdk; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install dotnet-8.0-sdk failed' }; \
    [Environment]::SetEnvironmentVariable('DOTNET_ROOT', 'C:\Program Files\dotnet', 'Machine'); \
    $$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine'); \
    [Environment]::SetEnvironmentVariable('Path', "$$machinePath;C:\Program Files\dotnet;C:\Users\ContainerAdministrator\.dotnet\tools", 'Machine'); \
    & 'C:\Program Files\dotnet\dotnet.exe' --version

ENV DOTNET_ROOT="C:\Program Files\dotnet"

RUN ["C:\\Program Files\\dotnet\\dotnet.exe", "tool", "install", "--global", "wix"]
RUN ["C:\\Program Files\\dotnet\\dotnet.exe", "tool", "install", "--global", "AzureSignTool"]
RUN ["C:\\Users\\ContainerAdministrator\\.dotnet\\tools\\wix.exe", "--version"]
RUN ["C:\\Program Files\\dotnet\\dotnet.exe", "tool", "list", "--global"]

COPY sign.ps1 C:/scripts/sign.ps1

ENTRYPOINT ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:\\scripts\\sign.ps1", "-Container"]
