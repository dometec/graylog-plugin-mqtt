package it.osys.graylog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link MQTTTransport#payloadToString(byte[])}: UTF-8 payloads pass through as text,
 * binary (non-UTF-8) payloads are rendered as hex.
 */
class MQTTTransportTest {

	@Test
	void nullOrEmptyPayloadBecomesEmptyString() {
		assertEquals("", MQTTTransport.payloadToString(null));
		assertEquals("", MQTTTransport.payloadToString(new byte[0]));
	}

	@Test
	void utf8PayloadIsKeptAsText() {
		assertEquals("hello", MQTTTransport.payloadToString("hello".getBytes(StandardCharsets.UTF_8)));
		assertEquals("càfé €", MQTTTransport.payloadToString("càfé €".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void binaryPayloadIsRenderedAsHex() {
		byte[] binary = { (byte) 0x08, (byte) 0x96, (byte) 0x01, (byte) 0xff, (byte) 0xfe };
		assertEquals("089601fffe", MQTTTransport.payloadToString(binary));
	}

	@Test
	void invalidUtf8SequenceIsRenderedAsHex() {
		// 0xC3 starts a two-byte sequence but is not followed by a valid continuation byte.
		byte[] malformed = { 'a', (byte) 0xc3, 'b' };
		assertEquals("61c362", MQTTTransport.payloadToString(malformed));
	}
}
