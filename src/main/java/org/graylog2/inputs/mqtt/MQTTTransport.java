package org.graylog2.inputs.mqtt;

import static com.codahale.metrics.MetricRegistry.name;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.BooleanField;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.CodecAggregator;
import org.graylog2.plugin.inputs.transports.Transport;
import org.graylog2.plugin.journal.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.google.common.base.Splitter;
import com.google.common.hash.Hashing;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

public class MQTTTransport implements Transport, MqttCallbackExtended, IMqttActionListener {

	private final Logger logger = LoggerFactory.getLogger(MQTTTransport.class);

	private static final String CK_BROKER_URL = "brokerUrl";
	private static final String CK_TOPICS = "topics";
	private static final String CK_TIMEOUT = "timeout";
	private static final String CK_KEEPALIVE = "keepalive";
	private static final String CK_PASSWORD = "password";
	private static final String CK_USERNAME = "username";
	private static final String CK_USE_AUTH = "useAuth";
	private static final String CK_CLIENTID = "clientId";
	private static final String CK_CLEAN_SESSION = "cleanSession";

	private final Configuration configuration;
	private final MetricRegistry metricRegistry;
	private ServerStatus serverStatus;
	private MqttAsyncClient client;

	private MessageInput messageInput;

	private Meter incomingMessages;
	private Meter incompleteMessages;
	private Meter processedMessages;

	@AssistedInject
	public MQTTTransport(@Assisted Configuration configuration, MetricRegistry metricRegistry, ServerStatus serverStatus) {
		this.configuration = configuration;
		this.metricRegistry = metricRegistry;
		this.serverStatus = serverStatus;
	}

	@Override
	public void setMessageAggregator(CodecAggregator codecAggregator) {
	}

	@Override
	public void launch(MessageInput messageInput) throws MisfireException {

		this.messageInput = messageInput;
		final String metricName = messageInput.getUniqueReadableId();

		this.incomingMessages = metricRegistry.meter(name(metricName, "incomingMessages"));
		this.incompleteMessages = metricRegistry.meter(name(metricName, "incompleteMessages"));
		this.processedMessages = metricRegistry.meter(name(metricName, "processedMessages"));

		final String clientId = configuration.getString(CK_USE_AUTH,
				"graylog_" + Hashing.murmur3_32().hashUnencodedChars(this.serverStatus.getNodeId().toString()).toString());

		MqttConnectOptions connOpts = new MqttConnectOptions();
		connOpts.setCleanSession(Boolean.valueOf(configuration.getString(CK_CLEAN_SESSION)));
		connOpts.setAutomaticReconnect(true);
		connOpts.setKeepAliveInterval(configuration.getInt(CK_KEEPALIVE));
		connOpts.setConnectionTimeout(configuration.getInt(CK_TIMEOUT));
		connOpts.setMaxInflight(1250);
		if (configuration.getBoolean(CK_USE_AUTH)) {
			connOpts.setUserName(configuration.getString(CK_USERNAME));
			connOpts.setPassword(configuration.getString(CK_PASSWORD).toCharArray());
		} else {
			connOpts.setUserName(clientId);
		}

		DisconnectedBufferOptions bufferOpts = new DisconnectedBufferOptions();
		bufferOpts.setBufferEnabled(true);
		bufferOpts.setBufferSize(250000);
		bufferOpts.setDeleteOldestMessages(true);
		bufferOpts.setPersistBuffer(false);

		try {

			client = new MqttAsyncClient(configuration.getString(CK_BROKER_URL), clientId, new MemoryPersistence());

			client.setBufferOpts(bufferOpts);
			client.setCallback(this);
			client.connect(connOpts, null, this);

		} catch (Exception e) {
			logger.error("Can't connect to MQTT Broker.", e);
			throw new MisfireException(e);
		}

	}

	@Override
	public void onSuccess(IMqttToken iMqttToken) {
		logger.debug("OnSuccess to MQTT Broker: {}.", iMqttToken);
	}

	@Override
	public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
		logger.error("Can't connect to MQTT Broker.", throwable);
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {

		if (reconnect) {
			logger.info("Reconnected to: {}.", serverURI);
		} else {
			logger.info("Initial connection to: {}.", serverURI);
		}

		new Thread(this::subscribeToTopic).start();
	}

	private void subscribeToTopic() {

		while (true) {

			try {

				Iterable<String> topics = Splitter.on(',').omitEmptyStrings().trimResults().split(configuration.getString(CK_TOPICS));

				for (String topic : topics) {
					logger.info("Subscription to topic {} ...", topic);
					client.subscribe(topic, 0);
				}

				logger.info("Subscribed.");

				break;

			} catch (MqttException e) {
				logger.error("Can't subscribe to MQTT broker topic. Retry...", e);
			}

			try {
				Thread.sleep(10_000);
			} catch (InterruptedException e1) {
				logger.error("Interrupted Exception", e1);
				Thread.currentThread().interrupt();
			}
		}

	}

	@Override
	public void stop() {

		logger.info("Stopping of {}.", this.getClass().getName());

		if (!client.isConnected())
			return;

		try {
			client.disconnect();
			logger.info("Disconnected form MQTT Broker.");
		} catch (MqttException e) {
			logger.error("Error disconnecting from broker.", e);
		}

	}

	@Override
	public void connectionLost(Throwable throwable) {
		logger.error("ConnectionLost to MQTT Broker.", throwable);
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) {
		logger.debug("Received message on {}: {}.", topic, message);
		incomingMessages.mark();

		if (message.getPayload().length == 0) {
			logger.debug("Received message is empty. Not processing.");
			incompleteMessages.mark();
			return;
		}

		if (message.isDuplicate()) {
			logger.debug("Received message is a duplicate. Not processing.");
			incompleteMessages.mark();
			return;
		}

		String mess = topic + "|" + new String(message.getPayload(), StandardCharsets.UTF_8);

		RawMessage rawMessage = new RawMessage(mess.getBytes(StandardCharsets.UTF_8));

		logger.debug("Parsed message successfully, message id: <{}>.", rawMessage.getId());
		messageInput.processRawMessage(rawMessage);

		processedMessages.mark();
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		logger.trace("Delivery complete to MQTT Broker: {}/{}.", token.getTopics(), token.getMessageId());
	}

	@Override
	public MetricSet getMetricSet() {
		return null;
	}

	@FactoryClass
	public interface Factory extends Transport.Factory<MQTTTransport> {
		@Override
		MQTTTransport create(Configuration configuration);

		@Override
		Config getConfig();
	}

	@ConfigClass
	public static class Config implements Transport.Config {
		@Override
		public ConfigurationRequest getRequestedConfiguration() {
			final ConfigurationRequest cr = new ConfigurationRequest();

			cr.addField(new TextField(CK_BROKER_URL, "Broker URL", "tcp://localhost:1883", "This is the URL of the MQTT broker."));

			cr.addField(
					new BooleanField(CK_CLEAN_SESSION, "Clean session option", true, "session preserve setting for MQTT client/broker."));

			cr.addField(
					new BooleanField(CK_USE_AUTH, "Use Authentication", false, "This is the username for connecting to the MQTT broker."));

			cr.addField(new TextField(CK_USERNAME, "Username", "", "This is the username for connecting to the MQTT broker.",
					ConfigurationField.Optional.OPTIONAL));

			cr.addField(new TextField(CK_PASSWORD, "Password", "", "This is the password for connecting to the MQTT broker.",
					ConfigurationField.Optional.OPTIONAL, TextField.Attribute.IS_PASSWORD));

			cr.addField(new TextField(CK_TOPICS, "Topic Names", "cluster/system/logs",
					"The comma-separated list of topics you are subscribing to (+ and # allowed)."));

			cr.addField(new TextField(CK_CLIENTID, "Cluent ID", "", "The ClientId of client."));

			cr.addField(new NumberField(CK_TIMEOUT, "Connection timeout", 30, "Amount of seconds to wait for connections"));

			cr.addField(new NumberField(CK_KEEPALIVE, "Keep-alive interval", 30,
					"Maximum amount of seconds to wait before sending keep-alive message"));

			return cr;
		}
	}
}
