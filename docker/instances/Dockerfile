# Runtime image for wp-instances-app. Build the jar on the host first:
#   ./mvnw -pl :wp-instances-app -am -DskipTests package
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system wp && useradd --system --gid wp --home-dir /app --shell /usr/sbin/nologin wp \
    && chown -R wp:wp /app

COPY --chown=wp:wp backend/wp-instances-app/target/wp-instances-app-0.1.0-SNAPSHOT.jar /app/app.jar

USER wp

EXPOSE 8082

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
