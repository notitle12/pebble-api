# Fetch the official security-patched JRE until its versioned container is published.
FROM ubuntu:22.04@sha256:0a9887931b0e38af2e3487ed39e29436b0535c4f3f52e57745baaf79d1898956 AS java-runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      arm64) url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_aarch64_linux_hotspot_21.0.13_9.tar.gz'; checksum='b1734db7ade49c3f6acc6ae06233b8c2dd90e3e2f00f67000ae1c972aaa64d8c' ;; \
      amd64) url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz'; checksum='a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd' ;; \
      *) echo 'Unsupported JRE architecture' >&2; exit 1 ;; \
    esac; \
    curl --fail --location --retry 3 "$url" --output /tmp/jre.tar.gz; \
    echo "$checksum  /tmp/jre.tar.gz" | sha256sum --check --strict; \
    mkdir -p /opt/java/openjdk; \
    tar --extract --gzip --file /tmp/jre.tar.gz --directory /opt/java/openjdk --strip-components=1 --no-same-owner; \
    rm /tmp/jre.tar.gz; \
    /opt/java/openjdk/bin/java -version

# No older Java layer or build-only downloader is included in the runtime image.
FROM ubuntu:22.04@sha256:0a9887931b0e38af2e3487ed39e29436b0535c4f3f52e57745baaf79d1898956
ENV JAVA_HOME=/opt/java/openjdk \
    JAVA_VERSION=21.0.13+9 \
    PATH=/opt/java/openjdk/bin:$PATH \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates fontconfig tzdata libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 pebble \
    && useradd --uid 10001 --gid pebble --no-create-home --shell /usr/sbin/nologin pebble \
    && mkdir -p /app/logs/admin-audit \
    && chown -R pebble:pebble /app/logs \
    && chmod 700 /app/logs/admin-audit
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
RUN java -version
WORKDIR /app
ARG JAR_FILE=build/libs/pebble-api-1.2.2.jar
COPY --chown=pebble:pebble ${JAR_FILE} /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-Djava.awt.headless=true", "-jar", "/app/app.jar"]
