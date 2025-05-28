package miniq.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link QConfig} record and its Builder.
 */
public class QConfigTest {

    @Test
    public void testBuilderWithAllFields() {
        // Create a QConfig with all fields set
        QConfig config = new QConfig.Builder()
                .DbName("testDb")
                .QueueName("testQueue")
                .QueueMaxSize(1000)
                .SqliteCacheSizeBytes(512000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        // Verify all fields are correctly set
        assertEquals("testDb.db", config.dbName());
        assertEquals("testQueue", config.queueName());
        assertEquals(1000, config.queueMaxSize());
        assertEquals(512000, config.sqliteCacheSizeBytes());
        assertTrue(config.createDb());
        assertTrue(config.createQueue());
    }

    @Test
    public void testBuilderWithDefaultValues() {
        // Create a QConfig with only some fields set
        QConfig config = new QConfig.Builder()
                .DbName("testDb")
                .build();

        // Verify fields are correctly set
        assertEquals("testDb.db", config.dbName());
        assertNull(config.queueName()); // Not set
        assertNull(config.queueMaxSize()); // Not set
        assertNull(config.sqliteCacheSizeBytes()); // Not set
        assertNull(config.createDb()); // Not set
        assertNull(config.createQueue()); // Not set
    }

    @Test
    public void testBuilderWithNullValues() {
        // Create a QConfig with null values
        QConfig config = new QConfig.Builder()
                .DbName(null)
                .QueueName(null)
                .QueueMaxSize(null)
                .SqliteCacheSizeBytes(null)
                .CreateDb(null)
                .CreateQueue(null)
                .build();

        // Verify fields are correctly set with defaults or null
        assertEquals("null.db", config.dbName()); // null + ".db" suffix
        assertEquals("queue", config.queueName()); // Default when null is passed
        assertEquals(Integer.MAX_VALUE, config.queueMaxSize()); // Default when null is passed
        assertEquals(256000, config.sqliteCacheSizeBytes()); // Default when null is passed
        assertNull(config.createDb());
        assertNull(config.createQueue());
    }

    @Test
    public void testQConfigEquality() {
        // Create two identical configs
        QConfig config1 = new QConfig.Builder()
                .DbName("testDb")
                .QueueName("testQueue")
                .QueueMaxSize(1000)
                .SqliteCacheSizeBytes(512000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        QConfig config2 = new QConfig.Builder()
                .DbName("testDb")
                .QueueName("testQueue")
                .QueueMaxSize(1000)
                .SqliteCacheSizeBytes(512000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        // Verify they are equal and have the same hash code
        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());

        // Create a different config
        QConfig config3 = new QConfig.Builder()
                .DbName("differentDb")
                .QueueName("testQueue")
                .QueueMaxSize(1000)
                .SqliteCacheSizeBytes(512000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        // Verify they are not equal
        assertNotEquals(config1, config3);
    }

    @Test
    public void testToString() {
        // Create a config
        QConfig config = new QConfig.Builder()
                .DbName("testDb")
                .QueueName("testQueue")
                .QueueMaxSize(1000)
                .SqliteCacheSizeBytes(512000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        // Verify toString contains all field values
        String toString = config.toString();
        assertTrue(toString.contains("testDb.db"));
        assertTrue(toString.contains("testQueue"));
        assertTrue(toString.contains("1000"));
        assertTrue(toString.contains("512000"));
        assertTrue(toString.contains("true"));
    }
}
