FROM eclipse-temurin:17-jre
WORKDIR /app
COPY fluxgate-envoy-extauth/target/fluxgate-envoy-extauth-0.4.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
