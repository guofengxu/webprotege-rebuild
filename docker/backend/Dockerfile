# Runtime image for wp-app. Build the jar on the host first:
#   ./mvnw -pl :wp-app -am -DskipTests package
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system wp && useradd --system --gid wp --home-dir /app --shell /usr/sbin/nologin wp \
    && mkdir -p /app/data \
    && chown -R wp:wp /app

COPY --chown=wp:wp backend/wp-app/target/wp-app-0.1.0-SNAPSHOT.jar /app/app.jar

USER wp

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
