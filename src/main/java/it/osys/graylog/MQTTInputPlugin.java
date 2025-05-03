package it.osys.graylog;

import java.util.Collection;
import java.util.Collections;

import org.graylog2.plugin.Plugin;
import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.PluginModule;

/**
 * Implement the Plugin interface here.
 */
public class MQTTInputPlugin implements Plugin {

	@Override
	public PluginMetaData metadata() {
		return new MQTTInputMetaData();
	}

	@Override
	public Collection<PluginModule> modules() {
		return Collections.<PluginModule> singletonList(new MQTTInputModule());
	}

}
