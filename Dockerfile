FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY when-common when-common
COPY when-observability when-observability
COPY when-sink-spi when-sink-spi
COPY when-sink-http when-sink-http
COPY when-sink-kafka when-sink-kafka
COPY when-delivery when-delivery
COPY when-api when-api
COPY when-admin-api when-admin-api
COPY when-admin-web when-admin-web
COPY when-storage-redis when-storage-redis
COPY when-cluster when-cluster
COPY when-timewheel when-timewheel
COPY when-ingress-router when-ingress-router
COPY when-test-support when-test-support
COPY when-app when-app
COPY when-e2e-test when-e2e-test
COPY when-acceptance when-acceptance
RUN ./mvnw -B -ntp -pl when-app -am package -DskipTests \
    && cp when-app/target/when-app-*-runner.jar /tmp/when-server.jar

FROM eclipse-temurin:17-jre-jammy AS runtime
ARG VERSION=dev
LABEL org.opencontainers.image.title="When" \
      org.opencontainers.image.description="When distributed delayed-delivery server" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN groupadd --gid 10001 when \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /opt/when when \
    && mkdir -p /opt/when/bin /opt/when/lib /opt/when/conf /opt/when/licenses /opt/when/var \
    && chown -R 10001:10001 /opt/when
COPY --from=build --chown=10001:10001 /tmp/when-server.jar /opt/when/lib/when-server.jar
COPY --chown=10001:10001 build/server/bin/when /opt/when/bin/when
COPY --chown=10001:10001 build/server/conf/application.example.yml /opt/when/conf/application.example.yml
COPY --chown=10001:10001 build/server/conf/logback.xml /opt/when/conf/logback.xml
COPY --chown=10001:10001 LICENSE /opt/when/licenses/LICENSE

USER 10001:10001
WORKDIR /opt/when
EXPOSE 8080 8081 9090
ENTRYPOINT ["/opt/when/bin/when", "run"]
