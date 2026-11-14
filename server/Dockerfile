FROM rust:1.95-alpine AS build
RUN apk add --no-cache build-base
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY web ./web
RUN cargo build --release --locked

FROM alpine:3.22
RUN mkdir /data && chown 10001:10001 /data && chmod 0700 /data
COPY --from=build /src/target/release/rennrad-tracker /usr/local/bin/rennrad-tracker
USER 10001:10001
EXPOSE 8787
ENTRYPOINT ["/usr/local/bin/rennrad-tracker"]
