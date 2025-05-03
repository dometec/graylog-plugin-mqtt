MQTT Input Plugin for Graylog
=======================

This is an input plugin that allows you to subscribe to several topic (or jist '#') on a [MQTT](http://mqtt.org) broker and index all receviced messages.

This library use Eclipse Paho as client mqtt library.

## Versioning 

| MQTT Input Plugin Version   | Graylog Version |
| -------- | -------          |
| 1.x      | 2.x, 3.x, 4x.    |
| 2.x      | 5.x (should), 6.x         |

This project is using Maven 3 and requires Java 8 for 1.x release and Java 17 for 2.x.

## Installation

[Download the plugin](https://github.com/dometec/graylog-plugin-mqtt.git/releases)
and place the `.jar` file in your Graylog plugin directory. The plugin directory
is the `plugins/` folder relative from your `graylog-server` directory by default
and can be configured in your `graylog.conf` file.

Restart `graylog-server` and you are done.

## Development


You can improve your development experience for the web interface part of your plugin
dramatically by making use of hot reloading. To do this, do the following:

* `git clone https://github.com/Graylog2/graylog2-server.git`
* `cd graylog2-server/graylog2-web-interface`
* `ln -s $YOURPLUGIN plugin/`
* `npm install && npm start`

## Usage

Create a new Input with "MQTT Raw" with MQTT connection detail's

## Getting started

This project is using Maven 3 and requires Java 8 or higher.

* Clone this repository.
* Run `mvn package` to build a JAR file.
* Optional: Run `mvn jdeb:jdeb` and `mvn rpm:rpm` to create a DEB and RPM package respectively.
* Copy generated JAR file in target directory to your Graylog plugin directory.
* Restart the Graylog.

## Plugin Release

We are using the maven release plugin:

```
$ mvn release:prepare
[...]
$ mvn release:perform
```

This sets the version numbers, creates a tag and pushes to GitHub.
