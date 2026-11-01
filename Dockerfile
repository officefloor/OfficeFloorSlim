# officefloorslim: the scan in a small image, for studies and CI. The runtime needs are a JRE
# and git (the growth reads the repository's history).
#
# Build:   docker build -t officefloorslim .
# Scan:    docker run --rm -v "$PWD:/repo" officefloorslim scan --format json --out scan.json
#
# The build resolves SinewSlim's libraries from Maven Central, at the sinewslimVersion in
# gradle.properties: there is no SinewSlim checkout beside it here.

FROM eclipse-temurin:17-jdk AS build
# the release passes the tag's version; without it, gradle.properties' development version
ARG VERSION
WORKDIR /src
COPY . /src
RUN ./gradlew --no-daemon --console=plain -q ${VERSION:+-Pversion=$VERSION} :officefloorslim-cli:fatJar

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
# a mounted repository is owned by another uid; without this git refuses it ("dubious ownership")
RUN git config --system --add safe.directory '*'
COPY --from=build /src/cli/build/libs/officefloorslim.jar /opt/officefloorslim/officefloorslim.jar
COPY bin/officefloorslim /usr/local/bin/officefloorslim
# Run unprivileged; the repository is mounted at /repo.
RUN useradd --create-home --uid 1001 slim
USER slim
WORKDIR /repo
ENTRYPOINT ["officefloorslim"]
CMD ["--help"]

LABEL org.opencontainers.image.title="officefloorslim" \
      org.opencontainers.image.description="Prices long Java methods two ways: extracted methods (SinewSlim) and functions OfficeFloor injects." \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/officefloor/OfficeFloorSlim"
