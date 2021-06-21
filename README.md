MQTT Plugin for Graylog
=======================

This is an input plugin that allows you to subscribe to an [MQTT](http://mqtt.org) broker and index all published messages.

Version updated to use Eclipse Paho as client library.

**Required Graylog version:** 2.4.0 and later

## Installation

[Download the plugin](https://github.com/graylog-labs/graylog-plugin-mqtt/releases)
and place the `.jar` file in your Graylog plugin directory. The plugin directory
is the `plugins/` folder relative from your `graylog-server` directory by default
and can be configured in your `graylog.conf` file.

Restart `graylog-server` and you are done.

## Build

This project is using Maven 3 and requires Java 8 or higher.

You can build a plugin (JAR) with `mvn package`.

DEB and RPM packages can be build with `mvn jdeb:jdeb` and `mvn rpm:rpm` respectively.
