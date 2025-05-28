package miniq.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link Message} record.
 */
public class MessageTest {

    @Test
    public void testMessageCreation() {
        // Create a message with all fields
        String messageId = "test-id-123";
        String topic = "test.topic";
        String data = "Test message data";
        MessageStatus status = MessageStatus.READY;
        Long inTime = System.currentTimeMillis();
        Long lockTime = inTime + 1000;
        Long doneTime = inTime + 2000;

        Message message = new Message(messageId, topic, data, status, inTime, lockTime, doneTime);

        // Verify all fields are correctly set
        assertEquals(messageId, message.messageId());
        assertEquals(topic, message.topic());
        assertEquals(data, message.data());
        assertEquals(status, message.status());
        assertEquals(inTime, message.inTime());
        assertEquals(lockTime, message.lockTime());
        assertEquals(doneTime, message.doneTime());
    }

    @Test
    public void testMessageWithNullFields() {
        // Create a message with some null fields
        String messageId = "test-id-456";
        String data = "Test message data";
        MessageStatus status = MessageStatus.READY;
        Long inTime = System.currentTimeMillis();

        // Topic, lockTime, and doneTime are null
        Message message = new Message(messageId, null, data, status, inTime, null, null);

        // Verify fields are correctly set
        assertEquals(messageId, message.messageId());
        assertNull(message.topic());
        assertEquals(data, message.data());
        assertEquals(status, message.status());
        assertEquals(inTime, message.inTime());
        assertNull(message.lockTime());
        assertNull(message.doneTime());
    }

    @Test
    public void testMessageEquality() {
        // Create two identical messages
        String messageId = "test-id-789";
        String topic = "test.topic";
        String data = "Test message data";
        MessageStatus status = MessageStatus.READY;
        Long inTime = 1620000000000L;
        Long lockTime = 1620000001000L;
        Long doneTime = 1620000002000L;

        Message message1 = new Message(messageId, topic, data, status, inTime, lockTime, doneTime);
        Message message2 = new Message(messageId, topic, data, status, inTime, lockTime, doneTime);

        // Verify they are equal and have the same hash code
        assertEquals(message1, message2);
        assertEquals(message1.hashCode(), message2.hashCode());

        // Create a different message
        Message message3 = new Message("different-id", topic, data, status, inTime, lockTime, doneTime);

        // Verify they are not equal
        assertNotEquals(message1, message3);
    }

    @Test
    public void testToString() {
        // Create a message
        String messageId = "test-id-abc";
        String topic = "test.topic";
        String data = "Test message data";
        MessageStatus status = MessageStatus.READY;
        Long inTime = 1620000000000L;
        Long lockTime = 1620000001000L;
        Long doneTime = 1620000002000L;

        Message message = new Message(messageId, topic, data, status, inTime, lockTime, doneTime);

        // Verify toString contains all field values
        String toString = message.toString();
        assertTrue(toString.contains(messageId));
        assertTrue(toString.contains(topic));
        assertTrue(toString.contains(data));
        assertTrue(toString.contains(status.toString()));
        assertTrue(toString.contains(inTime.toString()));
        assertTrue(toString.contains(lockTime.toString()));
        assertTrue(toString.contains(doneTime.toString()));
    }
}