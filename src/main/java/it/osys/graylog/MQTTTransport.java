package it.osys.graylog;

import static com.codahale.metrics.MetricRegistry.name;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.SerializationUtils;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.BooleanField;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
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

import io.netty.handler.codec.mqtt.MqttProperties;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;

public class MQTTTransport implements Transport {

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
	private static final String CK_QOS = "qos";
	private static final String CK_MQTT_VERSION = "mqttVersion";

	/** Stored value for MQTT 3.1.1 (Vert.x protocol level 4) */
	static final String MQTT_VERSION_311 = "311";
	/** Stored value for MQTT 5.0 (Vert.x protocol level 5) */
	static final String MQTT_VERSION_5 = "5";

	private final Configuration configuration;
	private final MetricRegistry metricRegistry;
	private final ServerStatus serverStatus;

	private Vertx vertx;
	private MqttClient client;
	private MessageInput messageInput;
	private volatile boolean running = false;

	private Meter incomingMessages;
	@SuppressWarnings("unused")
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
		this.running = true;

		final String metricName = messageInput.getUniqueReadableId();
		this.incomingMessages = metricRegistry.meter(name(metricName, "incomingMessages"));
		this.incompleteMessages = metricRegistry.meter(name(metricName, "incompleteMessages"));
		this.processedMessages = metricRegistry.meter(name(metricName, "processedMessages"));

		vertx = Vertx.vertx();
		connectToBroker();
	}

	private void connectToBroker() {
		final String brokerUrl = configuration.getString(CK_BROKER_URL);

		final String host;
		final int port;
		final boolean ssl;

		try {
			URI uri = new URI(brokerUrl);
			host = uri.getHost();
			int uriPort = uri.getPort();
			String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(java.util.Locale.ROOT) : "tcp";
			ssl = scheme.equals("ssl") || scheme.equals("mqtts");
			port = uriPort > 0 ? uriPort : (ssl ? 8883 : 1883);
		} catch (URISyntaxException e) {
			logger.error("Invalid broker URL: {}", brokerUrl, e);
			return;
		}

		String mqttVersion = configuration.getString(CK_MQTT_VERSION, MQTT_VERSION_311);
		boolean isMqtt5 = MQTT_VERSION_5.equals(mqttVersion);

		String clientId = configuration.getString(CK_CLIENTID, "");
		if (clientId == null || clientId.isBlank()) {
			clientId = "graylog_" + Hashing.murmur3_32_fixed().hashUnencodedChars(serverStatus.getNodeId().toString()).toString();
		}

		MqttClientOptions options = new MqttClientOptions();
		options.setClientId(clientId);
		options.setCleanSession(configuration.getBoolean(CK_CLEAN_SESSION));
		options.setKeepAliveInterval(configuration.getInt(CK_KEEPALIVE));
		options.setConnectTimeout(configuration.getInt(CK_TIMEOUT) * 1000);
		options.setVersion(isMqtt5 ? 5 : 4);
		options.setSsl(ssl);

		if (configuration.getBoolean(CK_USE_AUTH)) {
			options.setUsername(configuration.getString(CK_USERNAME));
			options.setPassword(configuration.getString(CK_PASSWORD));
		}

		client = MqttClient.create(vertx, options);

		client.publishHandler(this::onMessageArrived);

		client.closeHandler(v -> {
			if (running) {
				logger.warn("Connection to MQTT broker lost, reconnecting in 10s...");
				vertx.setTimer(10_000, id -> connectToBroker());
			}
		});

		client.exceptionHandler(ex -> logger.error("MQTT client error", ex));

		logger.info("Connecting to MQTT broker at {}:{} (MQTT {})...", host, port, isMqtt5 ? "5.0" : "3.1.1");

		client.connect(port, host)
				.onSuccess(ack -> {
					logger.info("Connected to MQTT broker at {}:{}.", host, port);
					subscribeToTopics();
				})
				.onFailure(ex -> {
					logger.error("Failed to connect to MQTT broker at {}:{}. Retrying in 10s...", host, port, ex);
					if (running) {
						vertx.setTimer(10_000, id -> connectToBroker());
					}
				});
	}

	private void subscribeToTopics() {
		int qos = configuration.getInt(CK_QOS);
		Iterable<String> topics = Splitter.on(',').omitEmptyStrings().trimResults().split(configuration.getString(CK_TOPICS));

		for (String topic : topics) {
			logger.info("Subscribing to topic {} with QoS {}...", topic, qos);
			client.subscribe(topic, qos)
					.onSuccess(v -> logger.info("Subscribed to topic {}.", topic))
					.onFailure(ex -> logger.error("Failed to subscribe to topic {}.", topic, ex));
		}
	}

	private void onMessageArrived(MqttPublishMessage message) {
		logger.debug("Received message on {}: {}.", message.topicName(), message.payload());
		incomingMessages.mark();

		boolean isMqtt5 = MQTT_VERSION_5.equals(configuration.getString(CK_MQTT_VERSION, MQTT_VERSION_311));

		HashMap<String, Object> m = new HashMap<>();
		m.put("payload", message.payload().toString(StandardCharsets.UTF_8));
		m.put("topic", message.topicName());
		m.put("qos", message.qosLevel().value());
		m.put("duplicate", message.isDup());
		m.put("retained", message.isRetain());

		if (isMqtt5) {
			MqttProperties props = message.properties();
			if (props != null && !props.isEmpty()) {
				extractMqtt5Properties(props, m);
			}
		}

		RawMessage rm = new RawMessage(SerializationUtils.serialize(m));
		logger.debug("Parsed message successfully, message id: <{}>.", rm.getId());
		messageInput.processRawMessage(rm);
		processedMessages.mark();
	}

	// MQTT 5.0 property IDs (from MQTT 5.0 spec, same as Netty package-private constants)
	private static final int PROP_PAYLOAD_FORMAT_INDICATOR  = 0x01;
	private static final int PROP_MESSAGE_EXPIRY_INTERVAL   = 0x02;
	private static final int PROP_CONTENT_TYPE             = 0x03;
	private static final int PROP_RESPONSE_TOPIC           = 0x08;
	private static final int PROP_CORRELATION_DATA         = 0x09;
	private static final int PROP_SUBSCRIPTION_IDENTIFIER  = 0x0B;
	private static final int PROP_TOPIC_ALIAS              = 0x23;
	private static final int PROP_USER_PROPERTY            = 0x26;

	private void extractMqtt5Properties(MqttProperties props, HashMap<String, Object> m) {
		MqttProperties.MqttProperty<?> prop;

		prop = props.getProperty(PROP_PAYLOAD_FORMAT_INDICATOR);
		if (prop != null) {
			m.put("mqtt5_payload_format_indicator", prop.value());
		}

		prop = props.getProperty(PROP_MESSAGE_EXPIRY_INTERVAL);
		if (prop != null) {
			m.put("mqtt5_message_expiry_interval", prop.value());
		}

		prop = props.getProperty(PROP_TOPIC_ALIAS);
		if (prop != null) {
			m.put("mqtt5_topic_alias", prop.value());
		}

		prop = props.getProperty(PROP_RESPONSE_TOPIC);
		if (prop != null) {
			m.put("mqtt5_response_topic", prop.value());
		}

		prop = props.getProperty(PROP_CORRELATION_DATA);
		if (prop != null && prop.value() instanceof byte[] bytes) {
			m.put("mqtt5_correlation_data", new String(bytes, StandardCharsets.UTF_8));
		}

		prop = props.getProperty(PROP_CONTENT_TYPE);
		if (prop != null) {
			m.put("mqtt5_content_type", prop.value());
		}

		prop = props.getProperty(PROP_SUBSCRIPTION_IDENTIFIER);
		if (prop != null) {
			m.put("mqtt5_subscription_identifier", prop.value());
		}

		@SuppressWarnings("rawtypes")
		List<? extends MqttProperties.MqttProperty> userProps = props.getProperties(PROP_USER_PROPERTY);
		if (userProps != null && !userProps.isEmpty()) {
			HashMap<String, String> userPropsMap = new HashMap<>();
			for (@SuppressWarnings("rawtypes") MqttProperties.MqttProperty up : userProps) {
				if (up instanceof MqttProperties.UserProperty userProp) {
					userPropsMap.put(userProp.value().key, userProp.value().value);
				}
			}
			if (!userPropsMap.isEmpty()) {
				m.put("mqtt5_user_properties", userPropsMap);
			}
		}
	}

	@Override
	public void stop() {
		logger.info("Stopping MQTT transport.");
		running = false;

		if (client != null && client.isConnected()) {
			client.disconnect().onComplete(ar -> {
				if (vertx != null) {
					vertx.close();
				}
			});
		} else if (vertx != null) {
			vertx.close();
		}
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

			cr.addField(new TextField(CK_BROKER_URL, "Broker URL", "tcp://localhost:1883",
					"URL of the MQTT broker (e.g. tcp://host:1883, ssl://host:8883)."));

			Map<String, String> versionValues = new LinkedHashMap<>();
			versionValues.put(MQTT_VERSION_311, "MQTT 3.1.1");
			versionValues.put(MQTT_VERSION_5, "MQTT 5.0");
			cr.addField(new DropdownField(CK_MQTT_VERSION, "MQTT Version", MQTT_VERSION_311, versionValues,
					"MQTT protocol version. Choose MQTT 5.0 to capture extended message properties.",
					ConfigurationField.Optional.NOT_OPTIONAL));

			cr.addField(new BooleanField(CK_CLEAN_SESSION, "Clean session", true,
					"Session persistence setting for the MQTT client/broker."));

			cr.addField(new BooleanField(CK_USE_AUTH, "Use Authentication", false,
					"Enable username/password authentication."));

			cr.addField(new TextField(CK_USERNAME, "Username", "",
					"Username for connecting to the MQTT broker.", ConfigurationField.Optional.OPTIONAL));

			cr.addField(new TextField(CK_PASSWORD, "Password", "",
					"Password for connecting to the MQTT broker.", ConfigurationField.Optional.OPTIONAL,
					TextField.Attribute.IS_PASSWORD));

			cr.addField(new TextField(CK_TOPICS, "Topic Names", "#",
					"Comma-separated list of topics to subscribe to (wildcards + and # allowed)."));

			cr.addField(new NumberField(CK_QOS, "QoS Level", 0,
					"QoS level for subscriptions (0, 1, or 2)."));

			cr.addField(new TextField(CK_CLIENTID, "Client ID", "",
					"Client ID to use. Leave blank for auto-generated.", ConfigurationField.Optional.OPTIONAL));

			cr.addField(new NumberField(CK_TIMEOUT, "Connection timeout (s)", 30,
					"Seconds to wait for a connection to establish."));

			cr.addField(new NumberField(CK_KEEPALIVE, "Keep-alive interval (s)", 30,
					"Maximum seconds between keep-alive messages."));

			return cr;
		}
	}
}
