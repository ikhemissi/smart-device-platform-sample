# Shared image definition for all Java platform services.
# Build from the repository root (OCI-compatible: works with podman, docker and `az acr build`):
#   podman build -f services/Dockerfile --build-arg SERVICE=telemetry-decoder -t sdp/telemetry-decoder .

ARG JDK_IMAGE=mcr.microsoft.com/openjdk/jdk:25-ubuntu
ARG RUNTIME_IMAGE=mcr.microsoft.com/openjdk/jdk:25-distroless

FROM ${JDK_IMAGE} AS build
ARG SERVICE
ARG APPINSIGHTS_AGENT_VERSION=3.7.10
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY services/ services/
RUN test -n "${SERVICE}" || (echo "--build-arg SERVICE=<module> is required" && exit 1) \
 && ./mvnw -B -q -pl "services/${SERVICE}" -am package -DskipTests \
 && ./mvnw -B -q dependency:copy \
      -Dartifact="com.microsoft.azure:applicationinsights-agent:${APPINSIGHTS_AGENT_VERSION}" \
      -DoutputDirectory=/workspace/agent -Dmdep.stripVersion=true \
 && cp "services/${SERVICE}/target/${SERVICE}-"*.jar /workspace/app.jar \
 && java -Djarmode=tools -jar /workspace/app.jar extract --layers --launcher --destination /workspace/extracted \
 && mkdir /workspace/runtime \
 && for layer in dependencies spring-boot-loader snapshot-dependencies application; do \
      cp -a "/workspace/extracted/$layer/." /workspace/runtime/; \
    done

FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=build /workspace/agent/applicationinsights-agent.jar /app/applicationinsights-agent.jar
# Single copy of the merged Spring Boot layers: consecutive COPY --from steps can fail on the classic builder used by
# ACR Tasks ("layer does not exist").
COPY --from=build /workspace/runtime/ ./
# The agent only exports when APPLICATIONINSIGHTS_CONNECTION_STRING is set; authentication and role name
# are provided at deploy time (APPLICATIONINSIGHTS_AUTHENTICATION_STRING, APPLICATIONINSIGHTS_ROLE_NAME).
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/applicationinsights-agent.jar --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75"
# Non-root user provided by the Microsoft OpenJDK distroless image.
USER 101:101
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
