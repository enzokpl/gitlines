# Each architecture's base image is pinned by digest in toolchains.json.
ARG GRAALVM_IMAGE=ghcr.io/graalvm/native-image-community:25.0.2-ol8-20260120
FROM ${GRAALVM_IMAGE} AS build
USER root
ARG MAVEN_VERSION=3.9.11
RUN microdnf install -y git python3.11 curl tar gzip findutils && microdnf clean all
RUN curl --retry 3 --connect-timeout 20 --max-time 300 -fsSLo /tmp/maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && curl --retry 3 --connect-timeout 20 --max-time 300 -fsSLo /tmp/maven.sha512 "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz.sha512" \
    && echo "$(cat /tmp/maven.sha512)  /tmp/maven.tar.gz" | sha512sum -c - \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz /tmp/maven.sha512
ENV PATH="/opt/apache-maven-${MAVEN_VERSION}/bin:${PATH}"
RUN ln -sf /usr/bin/python3.11 /usr/local/bin/python3
WORKDIR /work
COPY . .
RUN bash scripts/build.sh \
    && mkdir /out \
    && cp dist/gitlines /out/gitlines \
    && { native-image --version; mvn -version; git --version; ldd --version; } > /out/toolchain.txt \
    && ldd /out/gitlines > /out/libraries.txt
# Export files, rather than a runtime container image.
FROM scratch AS export
COPY --from=build /out/ /
