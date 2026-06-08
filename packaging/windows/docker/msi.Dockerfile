FROM mcr.microsoft.com/windows/servercore:ltsc2022

SHELL ["powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    Invoke-WebRequest -UseBasicParsing https://dot.net/v1/dotnet-install.ps1 -OutFile C:\dotnet-install.ps1; \
    & C:\dotnet-install.ps1 -Channel 8.0 -InstallDir C:\dotnet; \
    Remove-Item C:\dotnet-install.ps1 -Force

ENV DOTNET_ROOT=C:\dotnet
ENV PATH=C:\dotnet;C:\Users\ContainerAdministrator\.dotnet\tools;C:\Windows\System32;C:\Windows;C:\Windows\System32\WindowsPowerShell\v1.0

RUN ["C:\\dotnet\\dotnet.exe", "tool", "install", "--global", "wix"]
RUN if (-not (Test-Path C:\Users\ContainerAdministrator\.dotnet\tools\wix.exe)) { throw 'WiX tool install did not produce wix.exe' }
