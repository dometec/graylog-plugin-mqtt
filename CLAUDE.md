# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Graylog **input plugin** that subscribes to MQTT topics and indexes received messages. Built on the [Vert.x MQTT client](https://vertx.io/docs/vertx-mqtt/java/), supporting both MQTT 3.1.1 and MQTT 5.0. Requires Java 21 and Maven 3. See `README.md` for the user-facing configuration/field reference and the plugin-version ↔ Graylog-version compatibility table.

## Build & test

```bash
# Build the shaded plugin JAR. ALWAYS pass -Dskip.web.build:
# the default build runs `yarn install` (frontend-maven-plugin), which fails
# unless the sibling graylog2-web-interface repo is checked out next to this one.
mvn package -DskipTests -Dskip.web.build
# Output: target/graylog-plugin-mqtt-<version>.jar

# Fast Java-only compile check (skips the web/yarn machinery entirely):
mvn -o compiler:compile

# Run the JUnit tests (also needs the web build skipped):
mvn test -Dskip.web.build

# Optional native packages:
mvn jdeb:jdeb   # .deb
mvn rpm:rpm     # .rpm
```

JUnit 5 tests live in `src/test` (`MQTTRawCodecTest` covers the protobuf decode path and the topic/config helpers). Two non-obvious test-toolchain constraints, handled in `pom.xml`: the parent's surefire `argLine` references the **mockito-core** jar as a `-javaagent` (so `mockito-core` is a test dep even though unused), and the graylog2-server **test-jar is intentionally NOT a dependency** because it registers a container-based JUnit `TestEngine` that aborts plain unit runs — so tests avoid constructing real Graylog `Message`/`RawMessage` objects and exercise the pure `decodeProtobufPayload` core instead. `package.json` declares jest/eslint for the web side, but the web entry point (`src/web/index.jsx`) is effectively empty.

**SNAPSHOT dependency:** this module pins `vertx-mqtt`/`vertx-core` `5.1.0-SNAPSHOT`. The parent POM's enforcer rules (no SNAPSHOTs) are deliberately overridden here. `~/.m2/repository` must already contain those SNAPSHOT artifacts or the build fails to resolve them.

## Architecture

### Plugin registration chain
Graylog discovers the plugin via `META-INF/services/org.graylog2.plugin.Plugin` → `MQTTInputPlugin` → `MQTTInputModule.configure()`, which wires three pieces with Guice:
- **Transport** `mqtt-transport` → `MQTTTransport`
- **Codec** `mqtt-raw-codec` → `MQTTRawCodec`
- **Input** → `MQTTRawInput` (display name *"MQTT TCP (Raw/Plaintext)"*)

`MQTTInput` (uses `GelfCodec`) is **legacy and not registered** — ignore it unless intentionally reviving GELF support. `MQTTInputMetaData` reports version/required-Graylog-version from `graylog-plugin.properties`.

### The Transport ↔ Codec contract (most important detail)
The two halves are coupled by an implicit data format, not a typed interface:

1. `MQTTTransport.onMessageArrived` builds a `HashMap<String,Object>` (keys: `payload` (UTF-8 string; non-UTF-8/binary payloads are stored as their hex representation), `payloadBytes` (raw `byte[]`, for binary-safe protobuf decoding), `topic`, `qos`, `duplicate`, `retained`, plus `mqtt5_*` for MQTT 5.0), **Java-serializes it** via `SerializationUtils.serialize(...)`, and hands the bytes to `messageInput.processRawMessage(new RawMessage(...))`.
2. `MQTTRawCodec.decode` **deserializes that same HashMap** and maps each key onto Graylog `Message` fields (`payload` → message body; `mqtt5_user_properties` nested map → flattened `mqtt5_user_<key>` fields).

**Consequence:** any field added/renamed in the transport's map must be read by the codec, and vice versa. Both sides must stay on the same serialization format. The MQTT-5 property IDs in the transport are hard-coded ints mirroring the (package-private) Netty MQTT constants.

### Protobuf decoding (`MQTTRawCodec`)
Optional per-topic protobuf decoding lives entirely in the codec (config fields `protobuf_schema` + `protobuf_topic_config`, both `TEXTAREA`):
- The pasted `.proto` is compiled at runtime via **protoc-jar** (a bundled `protoc` binary, shaded in along with `protobuf-java`; the shade plugin keeps only the 3.11.4 binaries). Compiled descriptors and parsed topic mappings are cached once per codec instance (lazy, double-checked in `ensureProtobufInitialized`).
- `decodeProtobufPayload(topic, bytes)` is the **pure core** (no Graylog `Message` dependency, so it is unit-testable directly — see `MQTTRawCodecTest`): returns `null` (no mapping → plain text), a success `ProtoResult` (TextFormat body + extracted fields), or an error `ProtoResult` (→ falls back to raw payload + `protobuf_decode_error` field). `decode()` is the thin glue that turns a `ProtoResult` into a `Message`.
- Decoding is gated on topic-filter match (MQTT `+`/`#` wildcards via `topicMatches`), independent of content-type.

### Transport lifecycle (`MQTTTransport`)
- `launch()` creates a single `Vertx` instance and calls `connectToBroker()`. The broker scheme (`tcp`/`ssl`/`mqtts`) decides TLS and default port (1883/8883).
- MQTT version maps to Vert.x protocol level: 3.1.1 → 4, 5.0 → 5.
- **Reconnect loop:** both `closeHandler` (connection lost) and connect failure re-schedule `connectToBroker()` after 10s while `running` is true. `stop()` flips `running=false` and closes client + vertx.
- **Oversized messages:** `setMaxMessageSize` is configurable (UI field "Max message size (KB)", default 128). With the current Netty stack an over-limit publish does **not** disconnect — Netty's decoder skips the payload and surfaces a `TooLongFrameException`, which `handleClientException` turns into a placeholder Graylog message ("MQTT message too large to be saved"); the connection stays open and keeps receiving.
