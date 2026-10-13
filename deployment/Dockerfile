FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS java
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 libfontconfig1 && rm -rf /var/lib/apt/lists/*
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/app/agent-service
WORKDIR /app
COPY agent-service/requirements.lock.txt /app/requirements.lock.txt
RUN pip install --no-cache-dir -r /app/requirements.lock.txt
COPY agent-service /app/agent-service
COPY --from=build /build/target/safebank-agent-0.6.0.jar /app/bank.jar
COPY src/main/resources/db/fixtures/mysql/v07-synthetic.sql /app/seed.sql
COPY deployment /app/deployment
ENTRYPOINT ["python", "/app/deployment/team_runtime.py"]
