package miniq.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link MessageStatus} enum.
 */
public class MessageStatusTest {

    @Test
    public void testEnumValues() {
        // Test that the enum has the expected values
        assertEquals(5, MessageStatus.values().length);
        assertEquals(MessageStatus.READY, MessageStatus.values()[0]);
        assertEquals(MessageStatus.LOCKED, MessageStatus.values()[1]);
        assertEquals(MessageStatus.DONE, MessageStatus.values()[2]);
        assertEquals(MessageStatus.FAILED, MessageStatus.values()[3]);
        assertEquals(MessageStatus.ARCHIVED, MessageStatus.values()[4]);
    }

    @Test
    public void testGetValue() {
        // Test that each enum value returns the expected integer value
        assertEquals(0, MessageStatus.READY.getValue());
        assertEquals(1, MessageStatus.LOCKED.getValue());
        assertEquals(2, MessageStatus.DONE.getValue());
        assertEquals(3, MessageStatus.FAILED.getValue());
        assertEquals(4, MessageStatus.ARCHIVED.getValue());
    }

    @Test
    public void testValueOf() {
        // Test that valueOf returns the expected enum value
        assertEquals(MessageStatus.READY, MessageStatus.valueOf("READY"));
        assertEquals(MessageStatus.LOCKED, MessageStatus.valueOf("LOCKED"));
        assertEquals(MessageStatus.DONE, MessageStatus.valueOf("DONE"));
        assertEquals(MessageStatus.FAILED, MessageStatus.valueOf("FAILED"));
        assertEquals(MessageStatus.ARCHIVED, MessageStatus.valueOf("ARCHIVED"));
    }

    @Test
    public void testValueOfInvalidValue() {
        // Test that valueOf throws an exception for invalid values
        assertThrows(IllegalArgumentException.class, () -> MessageStatus.valueOf("INVALID"));
    }
}