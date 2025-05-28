package miniq.core;

import miniq.config.QConfig;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link MiniQ}.
 * Tests the core functionality of the MiniQ message queue.
 */
public class MiniQTest {

    private MiniQ miniQ;
    private static final String TEST_DB = "test_miniq";
    private static final String TEST_QUEUE = "test_queue";

    @BeforeEach
    public void setUp() throws SQLException {
        // Create a new MiniQ instance with an in-memory database for testing
        QConfig config = new QConfig.Builder()
                .DbName(TEST_DB)
                .QueueName(TEST_QUEUE)
                .QueueMaxSize(100)
                .SqliteCacheSizeBytes(256000) // Set cache size explicitly
                .CreateDb(true) // Create a new database for each test
                .CreateQueue(true)
                .build();

        miniQ = new MiniQ(config);
    }

    @AfterEach
    public void tearDown() {
        // Close the connection and clean up
        if (miniQ != null) {
            miniQ.close();
        }
    }

    @Test
    public void testPutAndGet() throws SQLException {
        // Put a message in the queue
        String testData = "Test message data";
        Message putMessage = miniQ.put(testData);

        // Verify the message was created with the correct data
        assertNotNull(putMessage);
        assertEquals(testData, putMessage.data());
        assertEquals(MessageStatus.READY, putMessage.status());
        assertNotNull(putMessage.messageId());

        // Get the message by ID
        Message getMessage = miniQ.get(putMessage.messageId());

        // Verify the retrieved message matches the original
        assertNotNull(getMessage);
        assertEquals(putMessage.messageId(), getMessage.messageId());
        assertEquals(putMessage.data(), getMessage.data());
        assertEquals(putMessage.status(), getMessage.status());
    }

    @Test
    public void testPutWithTopic() throws SQLException {
        // Put a message with a topic
        String testData = "Test message with topic";
        String testTopic = "test.topic";
        Message putMessage = miniQ.put(testData, testTopic);

        // Verify the message was created with the correct topic
        assertNotNull(putMessage);
        assertEquals(testData, putMessage.data());
        assertEquals(testTopic, putMessage.topic());
        assertEquals(MessageStatus.READY, putMessage.status());
    }

    @Test
    public void testPopAndPeek() throws SQLException {
        // Put a message in the queue
        String testData = "Test message for pop";
        Message putMessage = miniQ.put(testData);

        // Peek at the message (should not change its status)
        Message peekMessage = miniQ.peek();
        assertNotNull(peekMessage);
        assertEquals(putMessage.messageId(), peekMessage.messageId());
        assertEquals(MessageStatus.READY, peekMessage.status());

        // Pop the message (should change its status to LOCKED)
        Message popMessage = miniQ.pop();
        assertNotNull(popMessage);
        assertEquals(putMessage.messageId(), popMessage.messageId());
        assertEquals(MessageStatus.LOCKED, popMessage.status());

        // Peek again (should return null as there are no more READY messages)
        Message peekAgain = miniQ.peek();
        assertNull(peekAgain);
    }

    @Test
    public void testPopWithRoutingPattern() throws SQLException {
        // Put messages with different topics
        miniQ.put("Message 1", "orders.created");
        miniQ.put("Message 2", "orders.updated");
        miniQ.put("Message 3", "users.created");

        // Pop with exact routing pattern
        Message popExact = miniQ.popWithRoutingPattern("orders.created");
        assertNotNull(popExact);
        assertEquals("orders.created", popExact.topic());
        assertEquals("Message 1", popExact.data());

        // Pop with wildcard routing pattern
        Message popWildcard = miniQ.popWithRoutingPattern("orders.*");
        assertNotNull(popWildcard);
        assertEquals("orders.updated", popWildcard.topic());
        assertEquals("Message 2", popWildcard.data());

        // Pop with another routing pattern
        Message popAnother = miniQ.popWithRoutingPattern("users.*");
        assertNotNull(popAnother);
        assertEquals("users.created", popAnother.topic());
        assertEquals("Message 3", popAnother.data());
    }

    @Test
    public void testSetDoneAndSetFailed() throws SQLException {
        // Put a message and pop it
        Message putMessage = miniQ.put("Test message for status update");
        Message popMessage = miniQ.pop();

        // Set the message as done
        int doneResult = miniQ.setDone(popMessage.messageId());
        assertEquals(1, doneResult); // Should affect 1 row

        // Verify the message status is updated
        Message doneMessage = miniQ.get(popMessage.messageId());
        assertNotNull(doneMessage);
        assertEquals(MessageStatus.DONE, doneMessage.status());

        // Put another message and pop it
        Message putMessage2 = miniQ.put("Test message for failed status");
        Message popMessage2 = miniQ.pop();

        // Set the message as failed
        int failedResult = miniQ.setFailed(popMessage2.messageId());
        assertEquals(1, failedResult); // Should affect 1 row

        // Verify the message status is updated
        Message failedMessage = miniQ.get(popMessage2.messageId());
        assertNotNull(failedMessage);
        assertEquals(MessageStatus.FAILED, failedMessage.status());
    }

    @Test
    public void testRetry() throws SQLException {
        // Put a message, pop it, and mark it as failed
        Message putMessage = miniQ.put("Test message for retry");
        Message popMessage = miniQ.pop();
        miniQ.setFailed(popMessage.messageId());

        // Verify the message is failed
        Message failedMessage = miniQ.get(popMessage.messageId());
        assertEquals(MessageStatus.FAILED, failedMessage.status());

        // Retry the message
        Optional<Integer> retryResult = miniQ.retry(popMessage.messageId());
        assertTrue(retryResult.isPresent());
        assertEquals(1, retryResult.get()); // Should affect 1 row

        // Verify the message is ready again
        Message retriedMessage = miniQ.get(popMessage.messageId());
        assertEquals(MessageStatus.READY, retriedMessage.status());

        // Pop the message again
        Message popAgain = miniQ.pop();
        assertNotNull(popAgain);
        assertEquals(popMessage.messageId(), popAgain.messageId());
    }

    @Test
    public void testGetByRoutingPattern() throws SQLException {
        // Put messages with different topics
        miniQ.put("Message 1", "orders.created");
        miniQ.put("Message 2", "orders.updated");
        miniQ.put("Message 3", "users.created");

        // Get messages by exact routing pattern
        List<Message> exactMessages = miniQ.getByRoutingPattern("orders.created", MessageStatus.READY);
        assertEquals(1, exactMessages.size());
        assertEquals("orders.created", exactMessages.get(0).topic());

        // Get messages by wildcard routing pattern
        List<Message> wildcardMessages = miniQ.getByRoutingPattern("orders.*", MessageStatus.READY);
        assertEquals(2, wildcardMessages.size());

        // Get all messages by routing pattern regardless of status
        List<Message> allMessages = miniQ.getAllByRoutingPattern("*.*");
        assertEquals(3, allMessages.size());
    }

    @Test
    public void testGetWithStatus() throws SQLException {
        // Put messages and change their statuses
        Message msg1 = miniQ.put("Ready message");
        Message msg2 = miniQ.put("Locked message");
        Message msg3 = miniQ.put("Done message");
        Message msg4 = miniQ.put("Failed message");

        // Pop and update statuses
        miniQ.pop(); // Locks msg1
        miniQ.setDone(msg1.messageId());

        miniQ.pop(); // Locks msg2

        miniQ.pop(); // Locks msg3
        miniQ.setDone(msg3.messageId());

        miniQ.pop(); // Locks msg4
        miniQ.setFailed(msg4.messageId());

        // Get messages with DONE status
        List<Message> doneMessages = miniQ.getWithStatus(MessageStatus.DONE);
        assertEquals(2, doneMessages.size());

        // Get messages with FAILED status
        List<Message> failedMessages = miniQ.getWithStatus(MessageStatus.FAILED);
        assertEquals(1, failedMessages.size());

        // Get messages with LOCKED status
        List<Message> lockedMessages = miniQ.getWithStatus(MessageStatus.LOCKED);
        assertEquals(1, lockedMessages.size());

        // Get messages with READY status
        List<Message> readyMessages = miniQ.getWithStatus(MessageStatus.READY);
        assertEquals(0, readyMessages.size());
    }

    @Test
    public void testPruneAndPurge() throws SQLException {
        // Put messages and change their statuses
        Message msg1 = miniQ.put("Message 1");
        Message msg2 = miniQ.put("Message 2");
        Message msg3 = miniQ.put("Message 3");

        // Pop and update statuses
        miniQ.pop(); // Locks msg1
        miniQ.setDone(msg1.messageId());

        miniQ.pop(); // Locks msg2
        miniQ.setFailed(msg2.messageId());

        // Verify we have 3 messages
        assertEquals(1, miniQ.getWithStatus(MessageStatus.READY).size());
        assertEquals(0, miniQ.getWithStatus(MessageStatus.LOCKED).size());
        assertEquals(1, miniQ.getWithStatus(MessageStatus.DONE).size());
        assertEquals(1, miniQ.getWithStatus(MessageStatus.FAILED).size());

        // Prune only DONE messages
        miniQ.prune(false);

        // Verify DONE messages are removed
        assertEquals(1, miniQ.getWithStatus(MessageStatus.READY).size());
        assertEquals(0, miniQ.getWithStatus(MessageStatus.LOCKED).size());
        assertEquals(0, miniQ.getWithStatus(MessageStatus.DONE).size());
        assertEquals(1, miniQ.getWithStatus(MessageStatus.FAILED).size());

        // Put another message
        miniQ.put("Message 4");

        // Prune DONE and FAILED messages
        miniQ.prune(true);

        // Verify FAILED messages are also removed
        assertEquals(2, miniQ.getWithStatus(MessageStatus.READY).size());
        assertEquals(0, miniQ.getWithStatus(MessageStatus.LOCKED).size());
        assertEquals(0, miniQ.getWithStatus(MessageStatus.DONE).size());
        assertEquals(0, miniQ.getWithStatus(MessageStatus.FAILED).size());

        // Purge all messages
        miniQ.purge();

        // Verify all messages are removed
        assertEquals(0, miniQ.getWithStatus(MessageStatus.READY).size());
    }

    @Test
    public void testQueueSizeAndEmptyFull() throws SQLException {
        // Initially the queue should be empty
        assertTrue(miniQ.empty());
        assertFalse(miniQ.full());

        // Put some messages
        for (int i = 0; i < 10; i++) {
            miniQ.put("Message " + i);
        }

        // Queue should not be empty now
        assertFalse(miniQ.empty());

        // Queue should not be full yet (max size is 100)
        assertFalse(miniQ.full());

        // Pop and mark some messages as done/failed
        for (int i = 0; i < 5; i++) {
            Message msg = miniQ.pop();
            if (i % 2 == 0) {
                miniQ.setDone(msg.messageId());
            } else {
                miniQ.setFailed(msg.messageId());
            }
        }

        // Check done/failed count
        assertEquals(5, miniQ.qsizeDoneFailed());
    }
}
