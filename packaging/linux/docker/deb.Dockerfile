FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates dpkg-dev tar gzip coreutils \
    && rm -rf /var/lib/apt/lists/*
