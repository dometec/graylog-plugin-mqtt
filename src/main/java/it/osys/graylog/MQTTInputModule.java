package it.osys.graylog;

import java.util.Collections;
import java.util.Set;

import org.graylog2.plugin.PluginConfigBean;
import org.graylog2.plugin.PluginModule;

/**
 * Extend the PluginModule abstract class here to add you plugin to the system.
 */
public class MQTTInputModule extends PluginModule {

	/**
	* Returns all configuration beans required by this plugin.
	*
	* Implementing this method is optional. The default method returns an
	empty {@link Set}.
	*/
	@Override
	public Set<? extends PluginConfigBean> getConfigBeans() {
		return Collections.emptySet();
	}

	@Override
	protected void configure() {

		addTransport("mqtt-transport", MQTTTransport.class, MQTTTransport.Config.class, MQTTTransport.Factory.class);

		addCodec("mqtt-raw-codec", MQTTRawCodec.class);

		addMessageInput(MQTTRawInput.class, MQTTRawInput.Factory.class);

	}

}
