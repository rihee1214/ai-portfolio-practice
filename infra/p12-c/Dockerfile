FROM eclipse-temurin@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY --chown=10001:10001 marketplace.jar /app/marketplace.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/marketplace.jar"]
