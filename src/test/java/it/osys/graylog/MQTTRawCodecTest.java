package it.osys.graylog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graylog2.plugin.configuration.Configuration;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

/**
 * Exercises the protobuf decode path via {@link MQTTRawCodec#decodeProtobufPayload(String, byte[])}, which
 * is independent of Graylog's {@code Message} type (and therefore of the Graylog runtime classpath).
 */
class MQTTRawCodecTest {

	private static final String SCHEMA = """
			syntax = "proto3";
			package test;
			enum Unit {
			  CELSIUS = 0;
			  FAHRENHEIT = 1;
			}
			message Location {
			  string room = 1;
			}
			message TempReading {
			  string id = 1;
			  double celsius = 2;
			  Location location = 3;
			  Unit unit = 4;
			}
			""";

	private static final String TOPIC_CONFIG =
			"sensors/+/temp = test.TempReading : id, celsius, location.room, unit";

	@Test
	void decodesProtobufAndExtractsSelectedFields() throws Exception {
		MQTTRawCodec.ProtoResult result =
				newCodec(SCHEMA, TOPIC_CONFIG).decodeProtobufPayload("sensors/a/temp", sampleTempReading());

		assertNull(result.error);
		// Selected fields, including a nested path and an enum rendered by name.
		assertEquals("sensor-1", result.fields.get("id"));
		assertEquals(21.5d, result.fields.get("celsius"));
		assertEquals("kitchen", result.fields.get("location.room"));
		assertEquals("FAHRENHEIT", result.fields.get("unit"));
		// Full decode shown in the message body (TextFormat).
		assertTrue(result.body.contains("sensor-1"), "body should contain the decoded value, was: " + result.body);
	}

	@Test
	void returnsNullWhenTopicHasNoMapping() throws Exception {
		assertNull(newCodec(SCHEMA, TOPIC_CONFIG).decodeProtobufPayload("other/topic", sampleTempReading()));
	}

	@Test
	void reportsErrorWhenMappingReferencesUnknownType() throws Exception {
		MQTTRawCodec.ProtoResult result =
				newCodec(SCHEMA, "sensors/+/temp = test.DoesNotExist").decodeProtobufPayload("sensors/a/temp", sampleTempReading());

		assertNull(result.body);
		assertTrue(result.error.contains("test.DoesNotExist"));
	}

	@Test
	void reportsErrorWhenNoBinaryPayload() {
		MQTTRawCodec.ProtoResult result =
				newCodec(SCHEMA, TOPIC_CONFIG).decodeProtobufPayload("sensors/a/temp", null);

		assertNull(result.body);
		assertTrue(result.error.contains("No binary payload"));
	}

	@Test
	void topicMatchingHonoursMqttWildcards() {
		assertTrue(MQTTRawCodec.topicMatches("sensors/+/temp", "sensors/a/temp"));
		assertFalse(MQTTRawCodec.topicMatches("sensors/+/temp", "sensors/a/b/temp"));
		assertTrue(MQTTRawCodec.topicMatches("events/#", "events/a/b/c"));
		assertTrue(MQTTRawCodec.topicMatches("events/#", "events"));
		assertFalse(MQTTRawCodec.topicMatches("a/b", "a/b/c"));
	}

	@Test
	void parsesTopicConfigGrammar() {
		List<MQTTRawCodec.TopicMapping> mappings = MQTTRawCodec.parseTopicConfig(
				"# a comment\n"
				+ "sensors/+/temp = test.TempReading : id, celsius\n"
				+ "events/# = test.Event\n");

		assertEquals(2, mappings.size());
		assertEquals("sensors/+/temp", mappings.get(0).filter);
		assertEquals("test.TempReading", mappings.get(0).typeName);
		assertEquals(List.of("id", "celsius"), mappings.get(0).fields);
		assertEquals("test.Event", mappings.get(1).typeName);
		assertTrue(mappings.get(1).fields.isEmpty());
	}

	// --- helpers -------------------------------------------------------

	private MQTTRawCodec newCodec(String schema, String topicConfig) {
		Map<String, Object> cfg = new HashMap<>();
		cfg.put(MQTTRawCodec.CK_PROTOBUF_SCHEMA, schema);
		cfg.put(MQTTRawCodec.CK_PROTOBUF_TOPIC_CONFIG, topicConfig);
		return new MQTTRawCodec(new Configuration(cfg), new ObjectMapper(), null);
	}

	private static byte[] sampleTempReading() throws Exception {
		Map<String, Descriptors.Descriptor> types = MQTTRawCodec.compileSchema(SCHEMA);
		Descriptors.Descriptor tempType = types.get("test.TempReading");
		Descriptors.Descriptor locType = types.get("test.Location");

		DynamicMessage location = DynamicMessage.newBuilder(locType)
				.setField(locType.findFieldByName("room"), "kitchen")
				.build();

		Descriptors.FieldDescriptor unitFd = tempType.findFieldByName("unit");
		return DynamicMessage.newBuilder(tempType)
				.setField(tempType.findFieldByName("id"), "sensor-1")
				.setField(tempType.findFieldByName("celsius"), 21.5d)
				.setField(tempType.findFieldByName("location"), location)
				.setField(unitFd, unitFd.getEnumType().findValueByName("FAHRENHEIT"))
				.build()
				.toByteArray();
	}
}
