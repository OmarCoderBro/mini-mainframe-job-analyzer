FROM eclipse-temurin:21-jdk

# Install Scala 2.13 — minimal install, no sbt or coursier.
# The scala-lang.org GitHub releases page is the canonical download location.
ARG SCALA_VERSION=2.13.12
ENV SCALA_HOME=/usr/local/scala
ENV PATH="${SCALA_HOME}/bin:${PATH}"

RUN apt-get update -qq && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fSL \
       "https://github.com/scala/scala/releases/download/v${SCALA_VERSION}/scala-${SCALA_VERSION}.tgz" \
       -o /tmp/scala.tgz \
    && tar -xzf /tmp/scala.tgz -C /usr/local \
    && mv /usr/local/scala-${SCALA_VERSION} ${SCALA_HOME} \
    && rm /tmp/scala.tgz \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy project files
COPY src/       ./src/
COPY data/      ./data/
COPY job.conf   ./job.conf
COPY run.sh     ./run.sh
COPY test.sh    ./test.sh

RUN mkdir -p output build/java build/scala \
    && chmod +x run.sh test.sh

CMD ["./run.sh"]
