FROM amazoncorretto:11

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
ENV MAVEN_VERSION=3.9.9
ENV MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}
ENV JAVA_HOME=/usr/lib/jvm/java-11-amazon-corretto
ENV HOME=/home/jenkins
ENV PATH=${MAVEN_HOME}/bin:/usr/share/dotnet:${JAVA_HOME}/bin:${PATH}

RUN yum install -y tar gzip git \
    && curl -fsSL "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar -xzC /opt \
    && curl -fsSL https://dot.net/v1/dotnet-install.sh \
        | bash -s -- --channel 8.0 --install-dir /usr/share/dotnet \
    && mkdir -p /home/jenkins/.m2/repository
