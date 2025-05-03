package it.osys.graylog;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.Version;

/**
 * Implement the PluginMetaData interface here.
 */
public class MQTTInputMetaData implements PluginMetaData {
	
	private static final String PLUGIN_PROPERTIES = "it.osys.graylog.graylog-plugin-mqtt/graylog-plugin.properties";

	@Override
	public String getUniqueId() {
		return "it.osys.graylog.MqttInputPlugin";
	}

	@Override
	public String getName() {
		return "MQTTInput";
	}

	@Override
	public String getAuthor() {
		return "Domenico Briganti <domenico.briganti@osys.it>";
	}

	@Override
	public URI getURL() {
		return URI.create("https://github.com/dometec/graylog-plugin-mqtt");
	}

	@Override
	public Version getVersion() {
		return Version.fromPluginProperties(getClass(), PLUGIN_PROPERTIES, "version", Version.from(6, 1, 0, "unknown"));
	}

	@Override
	public String getDescription() {
		return "Process messages from one or multiple topics of an MQTT broker.";
	}

	@Override
	public Version getRequiredVersion() {
		return Version.fromPluginProperties(getClass(), PLUGIN_PROPERTIES, "graylog.version", Version.from(6, 1, 0, "unknown"));
	}

	@Override
	public Set<ServerStatus.Capability> getRequiredCapabilities() {
		return Collections.emptySet();
	}
}
