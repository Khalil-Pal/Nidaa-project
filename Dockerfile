# Nidaa — application image (DEP-3).
#
# Two stages: Maven builds the executable jar, a JRE-only image runs it as a
# non-root user. The repository ships application.properties.example (every
# value comes from environment variables) and git-ignores the real
# application.properties, so the build copies the example into place when no
# real file is present; .dockerignore keeps .env and the real file out of the
# image so no secret is ever baked in.

# ---- build ------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Wrapper first so the dependency download layer is cached across code changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline || true

COPY src src
RUN if [ ! -f src/main/resources/application.properties ]; then \
      cp src/main/resources/application.properties.example src/main/resources/application.properties; \
    fi \
 && ./mvnw -q -B package -DskipTests \
 && mv target/*.jar target/app.jar

# ---- run --------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 --no-create-home nidaa
COPY --from=build /app/target/app.jar app.jar
USER nidaa
EXPOSE 8081
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
