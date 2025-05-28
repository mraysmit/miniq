package miniq.client.impl;

import miniq.client.api.MessageConsumer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link SimpleMessageConsumer}.
 */
public class SimpleMessageConsumerTest {

    private MiniQ miniQ;
    private MessageConsumer consumer;

    @BeforeEach
    public void setUp() throws SQLException {
        // Create a MiniQ instance with an in-memory database for testing
        QConfig config = new QConfig.Builder()
                .DbName("test_consumer")
                .QueueName("test_queue")
                .QueueMaxSize(100)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        miniQ = new MiniQ(config);
        consumer = new SimpleMessageConsumer(miniQ);
    }

    @AfterEach
    public void tearDown() {
        consumer.close();
        miniQ.close();
    }

    @Test
    public void testReceiveMessage() throws SQLException, ExecutionException, InterruptedException {
        // Put a message in the queue
        String testData = "Test message data";
        Message putMessage = miniQ.put(testData);

        // Receive the message
        CompletableFuture<Optional<Message>> future = consumer.receiveMessage();
        Optional<Message> messageOpt = future.get();

        // Verify the message was received
        assertTrue(messageOpt.isPresent());
        Message message = messageOpt.get();
        assertEquals(putMessage.messageId(), message.messageId());
        assertEquals(testData, message.data());
        assertEquals(MessageStatus.LOCKED, message.status());
    }

    @Test
    public void testReceiveMessageWithTopic() throws SQLException, ExecutionException, InterruptedException {
        // Put messages with different topics
        miniQ.put("Message 1", "orders.created");
        miniQ.put("Message 2", "orders.updated");
        miniQ.put("Message 3", "users.created");

        // Receive a message with a specific topic pattern
        CompletableFuture<Optional<Message>> future = consumer.receiveMessage("orders.*");
        Optional<Message> messageOpt = future.get();

        // Verify the message was received
        assertTrue(messageOpt.isPresent());
        Message message = messageOpt.get();
        assertTrue(message.topic().startsWith("orders."));
    }

    @Test
    public void testAcknowledgeMessage() throws SQLException, ExecutionException, InterruptedException {
        // Put a message in the queue
        String testData = "Test message for acknowledgement";
        Message putMessage = miniQ.put(testData);

        // Receive the message
        CompletableFuture<Optional<Message>> future = consumer.receiveMessage();
        Optional<Message> messageOpt = future.get();
        assertTrue(messageOpt.isPresent());
        Message message = messageOpt.get();

        // Acknowledge the message
        CompletableFuture<Void> ackFuture = consumer.acknowledgeMessage(message.messageId());
        ackFuture.get();

        // Verify the message status is updated
        Message updatedMessage = miniQ.get(message.messageId());
        assertEquals(MessageStatus.DONE, updatedMessage.status());
    }

    @Test
    public void testRejectMessage() throws SQLException, ExecutionException, InterruptedException {
        // Put a message in the queue
        String testData = "Test message for rejection";
        Message putMessage = miniQ.put(testData);

        // Receive the message
        CompletableFuture<Optional<Message>> future = consumer.receiveMessage();
        Optional<Message> messageOpt = future.get();
        assertTrue(messageOpt.isPresent());
        Message message = messageOpt.get();

        // Reject the message
        CompletableFuture<Void> rejectFuture = consumer.rejectMessage(message.messageId());
        rejectFuture.get();

        // Verify the message status is updated
        Message updatedMessage = miniQ.get(message.messageId());
        assertEquals(MessageStatus.FAILED, updatedMessage.status());
    }

    @Test
    public void testOnMessage() throws SQLException, InterruptedException {
        // Create a latch to wait for the callback
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Message> receivedMessage = new AtomicReference<>();

        // Register a callback
        consumer.onMessage(message -> {
            receivedMessage.set(message);
            latch.countDown();
        });

        // Put a message in the queue
        String testData = "Test message for callback";
        Message putMessage = miniQ.put(testData);

        // Wait for the callback to be invoked
        boolean callbackInvoked = latch.await(5, TimeUnit.SECONDS);

        // Verify the callback was invoked
        assertTrue(callbackInvoked);
        assertNotNull(receivedMessage.get());
        assertEquals(testData, receivedMessage.get().data());
    }

    @Test
    public void testOnMessageWithTopic() throws SQLException, InterruptedException {
        // Create a latch to wait for the callback
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Message> receivedMessage = new AtomicReference<>();

        System.out.println("[DEBUG_LOG] Starting testOnMessageWithTopic test");

        // Purge the queue to ensure it's empty
        miniQ.purge();
        System.out.println("[DEBUG_LOG] Purged the queue");

        // Put messages with different topics
        Message msg1 = miniQ.put("Message 1", "users.created");
        System.out.println("[DEBUG_LOG] Put message 1: " + msg1.data() + ", topic: " + msg1.topic() + ", id: " + msg1.messageId());

        Message msg2 = miniQ.put("Message 2", "orders.created");
        System.out.println("[DEBUG_LOG] Put message 2: " + msg2.data() + ", topic: " + msg2.topic() + ", id: " + msg2.messageId());

        // Register a callback for a specific topic pattern
        consumer.onMessage("orders.*", message -> {
            System.out.println("[DEBUG_LOG] Callback invoked with message: " + message.data() + ", topic: " + message.topic());
            receivedMessage.set(message);
            latch.countDown();
        });

        System.out.println("[DEBUG_LOG] Registered callback for topic pattern: orders.*");

        // Wait for the callback to be invoked
        System.out.println("[DEBUG_LOG] Waiting for callback to be invoked");
        boolean callbackInvoked = latch.await(5, TimeUnit.SECONDS);

        // Verify the callback was invoked for the correct message
        System.out.println("[DEBUG_LOG] Callback invoked: " + callbackInvoked);
        if (receivedMessage.get() != null) {
            System.out.println("[DEBUG_LOG] Received message: " + receivedMessage.get().data() + ", topic: " + receivedMessage.get().topic());
        } else {
            System.out.println("[DEBUG_LOG] Received message is null");
        }

        assertTrue(callbackInvoked);
        assertNotNull(receivedMessage.get());
        assertEquals("Message 2", receivedMessage.get().data());
        assertEquals("orders.created", receivedMessage.get().topic());
    }

    @Test
    public void testConstructorWithDefaultCallback() throws SQLException, InterruptedException {
        // Create a latch to wait for the callback
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Message> receivedMessage = new AtomicReference<>();

        // Create a consumer with a default callback
        MessageConsumer consumerWithDefaultCallback = new SimpleMessageConsumer(miniQ, message -> {
            receivedMessage.set(message);
            latch.countDown();
        });

        // Put a message in the queue
        String testData = "Test message for default callback";
        Message putMessage = miniQ.put(testData);

        // Register a callback to start polling (using a dummy callback)
        consumerWithDefaultCallback.onMessage(msg -> {});

        // Wait for the callback to be invoked
        boolean callbackInvoked = latch.await(5, TimeUnit.SECONDS);

        // Verify the callback was invoked
        assertTrue(callbackInvoked);
        assertNotNull(receivedMessage.get());
        assertEquals(testData, receivedMessage.get().data());

        // Clean up
        consumerWithDefaultCallback.close();
    }
}
