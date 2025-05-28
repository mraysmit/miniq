package miniq.core.utils;

import miniq.core.model.MessageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link QUtils} utility methods.
 */
public class QUtilsTest {

    @Test
    public void testMillistoDateTime() {
        // Test with a known timestamp
        long millis = 1620000000000L; // 2021-05-03 00:00:00
        String dateTime = QUtils.MillistoDateTime(millis);

        // Format the same timestamp using SimpleDateFormat for comparison
        String expected = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date(millis));

        assertEquals(expected, dateTime);
    }

    @Test
    public void testMillistoEpochDateTime() {
        // Test with a known timestamp
        long millis = 1620000000000L; // 2021-05-03 00:00:00
        String dateTime = QUtils.MillistoEpochDateTime(millis);

        // Format the same timestamp using DateTimeFormatter for comparison
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
        String expected = formatter.format(Instant.ofEpochMilli(millis));

        assertEquals(expected, dateTime);
    }

    @Test
    public void testGetStatusDoneFailedString() {
        // Test that the method returns a comma-separated string of DONE and FAILED status values
        String statusString = QUtils.getStatusDoneFailedString();
        assertEquals("2,3", statusString); // DONE=2, FAILED=3
    }

    @Test
    public void testGetMessageStatusString() {
        // Test with a single status
        String singleStatus = QUtils.getMessageStatusString(List.of(MessageStatus.READY));
        assertEquals("0", singleStatus);

        // Test with multiple statuses
        String multipleStatuses = QUtils.getMessageStatusString(List.of(MessageStatus.READY, MessageStatus.LOCKED));
        assertEquals("0,1", multipleStatuses);

        // Test with all statuses
        String allStatuses = QUtils.getMessageStatusString(List.of(
                MessageStatus.READY, MessageStatus.LOCKED, MessageStatus.DONE, 
                MessageStatus.FAILED, MessageStatus.ARCHIVED));
        assertEquals("0,1,2,3,4", allStatuses);
    }

    // Note: ResultSet methods are not tested directly as they require mocking
    // In a real-world scenario, we would add Mockito as a dependency and test these methods
}
