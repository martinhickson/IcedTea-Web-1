FROM ubuntu:24.04

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ENV DEBIAN_FRONTEND=noninteractive
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
ENV HOME=/home/jenkins

# Match GitHub Actions ubuntu-latest: Corretto 11, Maven, and .NET 8 SDK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl tar gzip git maven \
    && curl -fsSL https://corretto.aws/downloads/latest/amazon-corretto-11-x64-linux-jdk.tar.gz \
        | tar -xzC /opt \
    && mv /opt/amazon-corretto-11.* /opt/jdk11 \
    && curl -fsSL https://dot.net/v1/dotnet-install.sh \
        | bash -s -- --channel 8.0 --install-dir /usr/share/dotnet \
    && mkdir -p /home/jenkins/.m2/repository \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/jdk11
ENV PATH=/usr/share/dotnet:${JAVA_HOME}/bin:${PATH}

COPY .docker/workflows/build.sh /usr/local/lib/itw/build.sh

ENTRYPOINT ["/bin/bash", "-lc", "source /usr/local/lib/itw/build.sh && run_container_distribution_build"]
