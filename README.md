MQTT Input Plugin for Graylog
=======================

This is an input plugin that allows you to subscribe to one or more topics (or just `#`) on a [MQTT](http://mqtt.org) broker and index all received messages.

This plugin uses [Vert.x MQTT client](https://vertx.io/docs/vertx-mqtt/java/) and supports both **MQTT 3.1.1** and **MQTT 5.0**.

## Versioning

| MQTT Input Plugin Version | Graylog Version          | MQTT Version      |
|---------------------------|--------------------------|-------------------|
| 1.x                       | 2.x, 3.x, 4.x            |     3.x           |
| 2.x                       | 5.x, 6.x                 |     3.x           |
| 3.x                       | 7.x                      |     3.x           |
| 4.x                       | 7.x                      |   3.x and 5       |

This project requires Java 21 and Maven 3.

## Installation

[Download the plugin](https://github.com/dometec/graylog-plugin-mqtt/releases)
and place the `.jar` file in your Graylog plugin directory. The plugin directory
is the `plugins/` folder relative from your `graylog-server` directory by default
and can be configured in your `graylog.conf` file.

Restart `graylog-server` and you are done.

## Usage

Create a new Input of type **MQTT TCP (Raw/Plaintext)** and fill in the connection details.

### Configuration fields

| Field | Description |
|---|---|
| Broker URL | URL of the MQTT broker, e.g. `tcp://localhost:1883` or `ssl://localhost:8883` |
| MQTT Version | Protocol version: **MQTT 3.1.1** or **MQTT 5.0** |
| Clean session | Whether to start a clean session on each connect |
| Use Authentication | Enable username/password authentication |
| Username / Password | Credentials for broker authentication |
| Topic Names | Comma-separated list of topics to subscribe to (`+` and `#` wildcards allowed) |
| QoS Level | Quality of Service level: 0, 1, or 2 |
| Client ID | Client identifier (leave blank for auto-generated) |
| Connection timeout (s) | Seconds to wait for the connection to establish |
| Keep-alive interval (s) | Maximum seconds between keep-alive messages |

### Message fields

Every received message produces the following Graylog fields:

| Field | Description |
|---|---|
| `message` | Raw payload of the MQTT message (UTF-8 decoded) |
| `topic` | MQTT topic the message was received on |
| `qos` | QoS level of the message (0, 1, or 2) |
| `duplicate` | Whether this is a duplicate delivery |
| `retained` | Whether this is a retained message |

#### MQTT 5.0 additional fields

When **MQTT 5.0** is selected, the following fields are also populated if present in the message:

| Field | Description |
|---|---|
| `mqtt5_payload_format_indicator` | `0` = binary, `1` = UTF-8 |
| `mqtt5_message_expiry_interval` | Message expiry interval in seconds |
| `mqtt5_topic_alias` | Topic alias number |
| `mqtt5_response_topic` | Topic name for the response message |
| `mqtt5_correlation_data` | Correlation data (UTF-8 decoded) |
| `mqtt5_content_type` | MIME content type of the payload |
| `mqtt5_subscription_identifier` | Subscription identifier |
| `mqtt5_user_<key>` | One field per user property, named `mqtt5_user_<key>` |

## Development

### Building

```bash
# Build the plugin JAR (skips web UI build)
mvn package -DskipTests -Dskip.web.build
```

The shaded JAR is produced in `target/graylog-plugin-mqtt-<version>.jar`.

> **Note:** The parent POM enforcer rules (banned SNAPSHOT versions, Java 21 requirement) are
> overridden in this module to allow building with `vertx-mqtt:5.1.0-SNAPSHOT` and Java 21+.
> Ensure `~/.m2/repository` contains the vertx-mqtt and vertx-core 5.1.0-SNAPSHOT artifacts.

### Optional packages

```bash
mvn jdeb:jdeb   # Debian package
mvn rpm:rpm     # RPM package
```

### Hot-reload web interface

```
git clone https://github.com/Graylog2/graylog2-server.git
cd graylog2-server/graylog2-web-interface
ln -s $YOURPLUGIN plugin/
npm install && npm start
```

## Plugin Release

```bash
mvn release:prepare
mvn release:perform
```

This sets the version numbers, creates a tag and pushes to GitHub.
