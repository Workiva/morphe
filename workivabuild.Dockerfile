FROM clojure:lein-alpine as build

# Setup Leiningen Profile with Authentication
COPY ./workivabuild.profiles.clj /root/.lein/profiles.clj
ARG ARTIFACTORY_USER
ARG ARTIFACTORY_PASS

# Copy in Source
WORKDIR /build
COPY . /build

# Fetch Dependencies
RUN lein deps

# Run Tests
RUN lein test

# Build Docs
RUN lein docs
RUN cd ./documentation && tar cvfz "../morphe-docs.tgz" ./
ARG BUILD_ARTIFACTS_DOCUMENTATION=/build/morphe-docs.tgz

# Build Artifact
RUN lein jar
ARG BUILD_ARTIFACTS_JAVA=/build/target/morphe-*.jar

# Audit Artifacts
RUN lein pom
ARG BUILD_ARTIFACTS_AUDIT=/build/pom.xml

FROM scratch
