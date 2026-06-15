# syntax=docker/dockerfile:1

FROM mcr.microsoft.com/windows/servercore:ltsc2025

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    $gitInstaller = 'C:\git-installer.exe'; \
    Invoke-WebRequest -UseBasicParsing 'https://github.com/git-for-windows/git/releases/download/v2.47.1.windows.1/Git-2.47.1-64-bit.exe' -OutFile $gitInstaller; \
    Start-Process -FilePath $gitInstaller -ArgumentList '/VERYSILENT','/NORESTART','/NOCANCEL','/SP-' -Wait; \
    Remove-Item $gitInstaller -Force

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    $correttoZip = 'C:\corretto.zip'; \
    Invoke-WebRequest -UseBasicParsing 'https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip' -OutFile $correttoZip; \
    Expand-Archive -LiteralPath $correttoZip -DestinationPath 'C:\build-tools\tmp' -Force; \
    $jdkDir = Get-ChildItem 'C:\build-tools\tmp' -Directory | Select-Object -First 1; \
    Move-Item -LiteralPath $jdkDir.FullName -Destination 'C:\build-tools\jdk11'; \
    Remove-Item $correttoZip, 'C:\build-tools\tmp' -Recurse -Force

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    $mavenZip = 'C:\maven.zip'; \
    Invoke-WebRequest -UseBasicParsing 'https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile $mavenZip; \
    Expand-Archive -LiteralPath $mavenZip -DestinationPath 'C:\build-tools' -Force; \
    Remove-Item $mavenZip -Force

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    Invoke-WebRequest -UseBasicParsing https://dot.net/v1/dotnet-install.ps1 -OutFile C:\dotnet-install.ps1; \
    & C:\dotnet-install.ps1 -Channel 8.0 -InstallDir C:\dotnet; \
    Remove-Item C:\dotnet-install.ps1 -Force

ENV DOTNET_ROOT=C:\dotnet
ENV JDK11_HOME=C:\build-tools\jdk11
ENV JAVA_HOME=C:\build-tools\jdk11
ENV MAVEN_HOME=C:\build-tools\apache-maven-3.9.9
ENV PATH=C:\build-tools\jdk11\bin;C:\build-tools\apache-maven-3.9.9\bin;C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;C:\dotnet;C:\Users\ContainerAdministrator\.dotnet\tools;C:\Windows\System32;C:\Windows;C:\Windows\System32\WindowsPowerShell\v1.0

RUN ["C:\\dotnet\\dotnet.exe", "tool", "install", "--global", "wix"]
RUN ["C:\\dotnet\\dotnet.exe", "tool", "install", "--global", "AzureSignTool"]
RUN ["C:\\Users\\ContainerAdministrator\\.dotnet\\tools\\wix.exe", "--version"]
RUN ["C:\\dotnet\\dotnet.exe", "tool", "list", "--global"]

COPY sign.ps1 C:/scripts/sign.ps1

ENTRYPOINT ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:\\scripts\\sign.ps1", "-Container"]
