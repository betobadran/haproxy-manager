# Imagem do manager. Gere o jar antes:  mvn -q package -DskipTests   (na raiz do repositório)
FROM ubuntu:24.04
# haproxy é opcional no manager: usado só para validar (`haproxy -c`) antes de publicar
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jre-headless haproxy ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -m -d /home/hapm hapm && mkdir /data && chown hapm /data
COPY target/haproxy-manager-server.jar /opt/hapm/hapm-manager.jar
ENV HAPM_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
USER hapm
HEALTHCHECK --interval=15s --timeout=3s CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]
ENTRYPOINT ["java", "-jar", "/opt/hapm/hapm-manager.jar"]
