package it.osys.graylog;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.SerializationUtils;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.MessageFactory;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.inputs.annotations.Codec;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.AbstractCodec;
import org.graylog2.plugin.journal.RawMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.assistedinject.Assisted;

import jakarta.inject.Inject;

@Codec(name = "mqtt-raw-codec", displayName = "MQTT Message Codec")
public class MQTTRawCodec extends AbstractCodec {

	private MessageFactory messageFactory;

	@Inject
	public MQTTRawCodec(@Assisted Configuration configuration, ObjectMapper objectMapper, MessageFactory messageFactory) {
		super(configuration);
		this.messageFactory = messageFactory;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Message decode(final RawMessage rawMessage) {

		HashMap<String, Object> m = (HashMap<String, Object>) SerializationUtils.deserialize(rawMessage.getPayload());

		Message message = messageFactory.createMessage((String) m.get("payload"), "MQTT source", rawMessage.getTimestamp());
		message.addField("topic", m.get("topic"));
		message.addField("qos", m.get("qos"));
		message.addField("mqttmessageid", m.get("mqttmessageid"));
		message.addField("duplicate", m.get("duplicate"));
		message.addField("retained", m.get("retained"));

		// MQTT 5.0 properties
		addIfPresent(message, m, "mqtt5_payload_format_indicator");
		addIfPresent(message, m, "mqtt5_message_expiry_interval");
		addIfPresent(message, m, "mqtt5_topic_alias");
		addIfPresent(message, m, "mqtt5_response_topic");
		addIfPresent(message, m, "mqtt5_correlation_data");
		addIfPresent(message, m, "mqtt5_content_type");
		addIfPresent(message, m, "mqtt5_subscription_identifier");

		// User properties: stored as a nested map, flattened to individual fields
		Object userProps = m.get("mqtt5_user_properties");
		if (userProps instanceof Map<?, ?> userPropsMap) {
			for (Map.Entry<?, ?> entry : userPropsMap.entrySet()) {
				message.addField("mqtt5_user_" + entry.getKey(), entry.getValue());
			}
		}

		return message;
	}

	private void addIfPresent(Message message, HashMap<String, Object> m, String key) {
		Object value = m.get(key);
		if (value != null) {
			message.addField(key, value);
		}
	}

	@FactoryClass
	public interface Factory extends AbstractCodec.Factory<MQTTRawCodec> {

		@Override
		MQTTRawCodec create(Configuration configuration);

		@Override
		Config getConfig();

	}

	@ConfigClass
	public static class Config extends AbstractCodec.Config {
		@Override
		public ConfigurationRequest getRequestedConfiguration() {
			return super.getRequestedConfiguration();
		}
	}

}
