FROM mcr.microsoft.com/windows/servercore:ltsc2022

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG http_proxy
ARG https_proxy
ARG no_proxy
ARG ITW_NETWORK_DIAGNOSTICS

ENV HTTP_PROXY=$HTTP_PROXY \
    HTTPS_PROXY=$HTTPS_PROXY \
    NO_PROXY=$NO_PROXY \
    http_proxy=$http_proxy \
    https_proxy=$https_proxy \
    no_proxy=$no_proxy \
    ITW_NETWORK_DIAGNOSTICS=$ITW_NETWORK_DIAGNOSTICS

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1

COPY build-image.ps1 C:/scripts/build-image.ps1
RUN C:/scripts/build-image.ps1
RUN C:/scripts/build-image.ps1 -InstallTools

ENV DOTNET_ROOT="C:\Program Files\dotnet"

COPY sign.ps1 C:/scripts/sign.ps1

ENTRYPOINT ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:\\scripts\\sign.ps1", "-Container"]
