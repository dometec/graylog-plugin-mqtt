package org.graylog2.inputs.mqtt;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

import org.graylog2.plugin.Message;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.inputs.annotations.Codec;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.AbstractCodec;
import org.graylog2.plugin.journal.RawMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.assistedinject.Assisted;

@Codec(name = "mqtt-raw-codec", displayName = "MQTT Raw Codec")
public class MQTTRawCodec extends AbstractCodec {

	@Inject
	public MQTTRawCodec(@Assisted Configuration configuration, ObjectMapper objectMapper) {
		super(configuration);
	}

	@Nullable
	@Override
	public Message decode(@Nonnull final RawMessage rawMessage) {

		String payload = new String(rawMessage.getPayload(), StandardCharsets.UTF_8);
		int pipeIndex = payload.indexOf("|");
		String topic = payload.substring(0, pipeIndex);
		String mess = payload.substring(pipeIndex + 1);

		Message message = new Message(mess, null, rawMessage.getTimestamp());
		message.addField("topic", topic);

		return message;
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