# Etapa de construccion
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Etapa final
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/target/discovery-service-1.0.0.jar ./discovery-service-1.0.0.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "discovery-service-1.0.0.jar"]

# Construir imagen docker
# docker build -t discovery-service:1.0 .

# Ejecutar imagen docker
# docker run -d -p 8761:8761 --name discovery-service discovery-service:1.0
