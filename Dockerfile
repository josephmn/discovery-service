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
ARG NAME_APP
ARG JAR_VERSION
COPY --from=build /app/target/${NAME_APP}-${JAR_VERSION}.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
# ENTRYPOINT ["java", "-jar", "discovery-service-1.0.0.jar", "--spring.profiles.active=dev"]

# Construir imagen docker
# docker build -t discovery-service:1.0 .

# Ejecutar imagen docker
# docker run -d -p 8761:8761 --name discovery-service discovery-service:1.0

# Ejecutar imagen docker con enviroment and network
# docker run -d -p 8761:8761 --name discovery-service --network=azure-net --env CONFIG_SERVER=http://config-server:8888 discovery-service:1.0

# Creando una red de Docker
# docker network create azure-net
# docker run -p 8761:8761 --name discovery-service --network=azure-net config-server:1.0    #si quieres ejecutar en modo acoplado
# docker run -d -p 8761:8761 --name discovery-service --network=azure-net config-server:1.0     #ejecución modo desacoplado (-d)

# Verificar conexiones en la red Docker
# docker network inspect azure-net