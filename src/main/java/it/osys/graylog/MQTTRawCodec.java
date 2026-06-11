package it.osys.graylog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.SerializationUtils;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.MessageFactory;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.annotations.Codec;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.AbstractCodec;
import org.graylog2.plugin.journal.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.assistedinject.Assisted;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TextFormat;

import jakarta.inject.Inject;

@Codec(name = "mqtt-raw-codec", displayName = "MQTT Message Codec")
public class MQTTRawCodec extends AbstractCodec {

	private static final Logger logger = LoggerFactory.getLogger(MQTTRawCodec.class);

	static final String CK_PROTOBUF_SCHEMA = "protobuf_schema";
	static final String CK_PROTOBUF_TOPIC_CONFIG = "protobuf_topic_config";

	private final Configuration configuration;
	private final MessageFactory messageFactory;

	// Lazily built once per codec instance (one instance per input).
	private volatile boolean protobufInitialized = false;
	private Map<String, Descriptors.Descriptor> typesByName;
	private List<TopicMapping> mappings;
	private String schemaError;

	@Inject
	public MQTTRawCodec(@Assisted Configuration configuration, ObjectMapper objectMapper, MessageFactory messageFactory) {
		super(configuration);
		this.configuration = configuration;
		this.messageFactory = messageFactory;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Message decode(final RawMessage rawMessage) {

		HashMap<String, Object> m = (HashMap<String, Object>) SerializationUtils.deserialize(rawMessage.getPayload());

		String topic = m.get("topic") instanceof String t ? t : null;
		byte[] payloadBytes = m.get("payloadBytes") instanceof byte[] b ? b : null;

		ProtoResult proto = topic == null ? null : decodeProtobufPayload(topic, payloadBytes);

		Message message;
		if (proto != null && proto.body != null) {
			// Successful protobuf decode: TextFormat rendering as body + selected fields.
			message = messageFactory.createMessage(
					proto.body.isEmpty() ? "[empty protobuf message]" : proto.body,
					"MQTT source", rawMessage.getTimestamp());
			for (Map.Entry<String, Object> entry : proto.fields.entrySet()) {
				message.addField(entry.getKey(), entry.getValue());
			}
		} else {
			// Default behavior: treat the payload as a UTF-8 string.
			String payloadStr = m.get("payload") instanceof String s ? s : "";
			// Graylog ignores or drops events with an empty full_message; keep empty MQTT publishes visible.
			String messageText = payloadStr.isEmpty()
					? "[empty MQTT payload]"
					: payloadStr;
			message = messageFactory.createMessage(messageText, "MQTT source", rawMessage.getTimestamp());
			if (proto != null && proto.error != null) {
				message.addField("protobuf_decode_error", proto.error);
			}
		}

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

	/**
	 * Decodes a payload as protobuf when the topic matches a configured schema mapping. Returns
	 * {@code null} when no mapping applies (caller falls back to plain text), a success result with the
	 * TextFormat body and extracted fields, or an error result when the mapping matched but decoding failed.
	 * Pure (no Graylog {@code Message} dependency) so it can be unit-tested directly.
	 */
	ProtoResult decodeProtobufPayload(String topic, byte[] payloadBytes) {
		ensureProtobufInitialized();

		TopicMapping mapping = findMapping(topic);
		if (mapping == null) {
			return null;
		}

		Descriptors.Descriptor type = typesByName == null ? null : typesByName.get(mapping.typeName);
		if (type == null) {
			return ProtoResult.error(
					schemaError != null ? schemaError : "Unknown protobuf message type: " + mapping.typeName);
		}
		if (payloadBytes == null) {
			return ProtoResult.error("No binary payload available to decode");
		}

		try {
			DynamicMessage proto = DynamicMessage.parseFrom(type, payloadBytes);
			String body = TextFormat.printer().printToString(proto);
			Map<String, Object> fields = new LinkedHashMap<>();
			extractFields(fields, proto, mapping.fields);
			return ProtoResult.success(body, fields);
		} catch (Exception e) {
			logger.warn("Failed to decode protobuf message on topic {} with type {}: {}",
					topic, mapping.typeName, e.getMessage());
			return ProtoResult.error(e.getMessage());
		}
	}

	private TopicMapping findMapping(String topic) {
		if (mappings == null) {
			return null;
		}
		for (TopicMapping mapping : mappings) {
			if (topicMatches(mapping.filter, topic)) {
				return mapping;
			}
		}
		return null;
	}

	private void addIfPresent(Message message, HashMap<String, Object> m, String key) {
		Object value = m.get(key);
		if (value != null) {
			message.addField(key, value);
		}
	}

	// ---------------------------------------------------------------------
	// Protobuf field extraction
	// ---------------------------------------------------------------------

	private void extractFields(Map<String, Object> out, DynamicMessage proto, List<String> fields) {
		if (fields.isEmpty()) {
			// No explicit selection: extract all top-level scalar (non-repeated, non-message) fields.
			for (Descriptors.FieldDescriptor fd : proto.getDescriptorForType().getFields()) {
				if (fd.isRepeated() || fd.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
					continue;
				}
				Object value = convertValue(fd, proto.getField(fd));
				if (value != null) {
					out.put(fd.getName(), value);
				}
			}
			return;
		}
		for (String path : fields) {
			Object value = resolvePath(proto, path);
			if (value != null) {
				out.put(path, value);
			}
		}
	}

	/** Resolves a dotted field path, descending into nested messages. */
	private static Object resolvePath(com.google.protobuf.Message msg, String path) {
		String[] segments = path.split("\\.");
		com.google.protobuf.Message current = msg;
		for (int i = 0; i < segments.length; i++) {
			Descriptors.FieldDescriptor fd = current.getDescriptorForType().findFieldByName(segments[i]);
			if (fd == null) {
				return null;
			}
			boolean last = i == segments.length - 1;
			if (fd.isRepeated()) {
				// v1: repeated/map fields are stringified, no further traversal.
				return current.getField(fd).toString();
			}
			Object value = current.getField(fd);
			if (last) {
				return convertValue(fd, value);
			}
			if (fd.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
				return null; // cannot descend into a scalar
			}
			current = (com.google.protobuf.Message) value;
		}
		return null;
	}

	/** Converts a protobuf field value into something Graylog can index. */
	private static Object convertValue(Descriptors.FieldDescriptor fd, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Descriptors.EnumValueDescriptor enumValue) {
			return enumValue.getName();
		}
		if (value instanceof ByteString bytes) {
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		}
		if (value instanceof com.google.protobuf.Message nested) {
			return TextFormat.printer().shortDebugString(nested);
		}
		// Primitives (Integer, Long, Float, Double, Boolean) and String pass through.
		return value;
	}

	// ---------------------------------------------------------------------
	// Schema compilation and topic-mapping parsing (built once, cached)
	// ---------------------------------------------------------------------

	private void ensureProtobufInitialized() {
		if (protobufInitialized) {
			return;
		}
		synchronized (this) {
			if (protobufInitialized) {
				return;
			}
			this.mappings = parseTopicConfig(configuration.getString(CK_PROTOBUF_TOPIC_CONFIG));
			String schema = configuration.getString(CK_PROTOBUF_SCHEMA);
			if (schema != null && !schema.isBlank() && !mappings.isEmpty()) {
				try {
					this.typesByName = compileSchema(schema);
				} catch (Exception e) {
					this.schemaError = "Protobuf schema compilation failed: " + e.getMessage();
					logger.error(this.schemaError, e);
				}
			}
			this.protobufInitialized = true;
		}
	}

	/** Compiles pasted .proto text into an index of message descriptors keyed by fully-qualified name. */
	static Map<String, Descriptors.Descriptor> compileSchema(String schemaText) throws Exception {
		File baseTmp = new File(System.getProperty("java.io.tmpdir"));
		File tmpDir = Files.createTempDirectory(baseTmp.toPath(), "graylog-mqtt-proto").toFile();
		File protoFile = new File(tmpDir, "schema.proto");
		File descFile = new File(tmpDir, "schema.desc");
		try {
			Files.write(protoFile.toPath(), schemaText.getBytes(StandardCharsets.UTF_8));

			int exit = com.github.os72.protocjar.Protoc.runProtoc(new String[] {
					"--include_imports",
					"-I", tmpDir.getAbsolutePath(),
					"--descriptor_set_out=" + descFile.getAbsolutePath(),
					protoFile.getAbsolutePath()
			});
			if (exit != 0) {
				throw new IllegalStateException("protoc exited with code " + exit + " (see server log for details)");
			}

			FileDescriptorSet set;
			try (var in = Files.newInputStream(descFile.toPath())) {
				set = FileDescriptorSet.parseFrom(in);
			}

			Map<String, FileDescriptorProto> protoByName = new HashMap<>();
			for (FileDescriptorProto fdp : set.getFileList()) {
				protoByName.put(fdp.getName(), fdp);
			}
			Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
			for (FileDescriptorProto fdp : set.getFileList()) {
				buildFileDescriptor(fdp, protoByName, built);
			}

			Map<String, Descriptors.Descriptor> typesByName = new HashMap<>();
			for (Descriptors.FileDescriptor fd : built.values()) {
				indexMessages(fd.getMessageTypes(), typesByName);
			}
			return typesByName;
		} finally {
			deleteQuietly(descFile);
			deleteQuietly(protoFile);
			deleteQuietly(tmpDir);
		}
	}

	private static Descriptors.FileDescriptor buildFileDescriptor(FileDescriptorProto fdp,
			Map<String, FileDescriptorProto> protoByName, Map<String, Descriptors.FileDescriptor> built)
			throws Descriptors.DescriptorValidationException {
		Descriptors.FileDescriptor existing = built.get(fdp.getName());
		if (existing != null) {
			return existing;
		}
		List<Descriptors.FileDescriptor> deps = new ArrayList<>();
		for (String depName : fdp.getDependencyList()) {
			FileDescriptorProto depProto = protoByName.get(depName);
			if (depProto != null) {
				deps.add(buildFileDescriptor(depProto, protoByName, built));
			}
		}
		Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(fdp,
				deps.toArray(new Descriptors.FileDescriptor[0]));
		built.put(fdp.getName(), fd);
		return fd;
	}

	private static void indexMessages(List<Descriptors.Descriptor> types, Map<String, Descriptors.Descriptor> out) {
		for (Descriptors.Descriptor d : types) {
			out.put(d.getFullName(), d);
			indexMessages(d.getNestedTypes(), out);
		}
	}

	/** Parses the per-topic mapping config: {@code <topic-filter> = <Type> : <field>, <field>} per line. */
	static List<TopicMapping> parseTopicConfig(String config) {
		if (config == null || config.isBlank()) {
			return Collections.emptyList();
		}
		List<TopicMapping> result = new ArrayList<>();
		for (String rawLine : config.split("\\r?\\n")) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq < 0) {
				logger.warn("Ignoring protobuf mapping line without '=': {}", line);
				continue;
			}
			String filter = line.substring(0, eq).trim();
			String right = line.substring(eq + 1).trim();

			String typeName;
			List<String> fields;
			int colon = right.indexOf(':');
			if (colon < 0) {
				typeName = right.trim();
				fields = Collections.emptyList();
			} else {
				typeName = right.substring(0, colon).trim();
				fields = new ArrayList<>();
				for (String f : right.substring(colon + 1).split(",")) {
					String fieldPath = f.trim();
					if (!fieldPath.isEmpty()) {
						fields.add(fieldPath);
					}
				}
			}
			if (filter.isEmpty() || typeName.isEmpty()) {
				logger.warn("Ignoring incomplete protobuf mapping line: {}", line);
				continue;
			}
			result.add(new TopicMapping(filter, typeName, fields));
		}
		return result;
	}

	/** MQTT topic-filter matching with {@code +} (single level) and {@code #} (multi level) wildcards. */
	static boolean topicMatches(String filter, String topic) {
		String[] f = filter.split("/", -1);
		String[] t = topic.split("/", -1);
		for (int i = 0; i < f.length; i++) {
			String seg = f[i];
			if (seg.equals("#")) {
				return true; // matches this level and everything below
			}
			if (i >= t.length) {
				return false;
			}
			if (seg.equals("+")) {
				continue;
			}
			if (!seg.equals(t[i])) {
				return false;
			}
		}
		return f.length == t.length;
	}

	private static void deleteQuietly(File file) {
		try {
			if (file != null) {
				Files.deleteIfExists(file.toPath());
			}
		} catch (Exception e) {
			logger.debug("Could not delete temp file {}: {}", file, e.getMessage());
		}
	}

	/** Outcome of a protobuf decode attempt: success carries {@code body}+{@code fields}, failure carries {@code error}. */
	static final class ProtoResult {
		final String body;
		final Map<String, Object> fields;
		final String error;

		private ProtoResult(String body, Map<String, Object> fields, String error) {
			this.body = body;
			this.fields = fields;
			this.error = error;
		}

		static ProtoResult success(String body, Map<String, Object> fields) {
			return new ProtoResult(body, fields, null);
		}

		static ProtoResult error(String error) {
			return new ProtoResult(null, Collections.emptyMap(), error);
		}
	}

	/** One parsed line of the per-topic protobuf mapping. */
	static final class TopicMapping {
		final String filter;
		final String typeName;
		final List<String> fields;

		TopicMapping(String filter, String typeName, List<String> fields) {
			this.filter = filter;
			this.typeName = typeName;
			this.fields = fields;
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
			final ConfigurationRequest cr = super.getRequestedConfiguration();

			cr.addField(new TextField(CK_PROTOBUF_SCHEMA, "Protobuf schema (.proto)", "",
					"Optional. Paste a self-contained .proto schema (may declare several message types). "
							+ "Compiled at runtime; requires a writable temp directory on the Graylog host.",
					ConfigurationField.Optional.OPTIONAL, TextField.Attribute.TEXTAREA));

			cr.addField(new TextField(CK_PROTOBUF_TOPIC_CONFIG, "Protobuf topic mapping", "",
					"Optional. One line per topic: '<topic-filter> = <FullyQualifiedType> : <field1>, <field2>'. "
							+ "Topic filters allow + and # wildcards. Fields after ':' are extracted as Graylog fields "
							+ "(dotted paths traverse nested messages); omit them to extract all top-level scalar fields.",
					ConfigurationField.Optional.OPTIONAL, TextField.Attribute.TEXTAREA));

			return cr;
		}
	}

}
