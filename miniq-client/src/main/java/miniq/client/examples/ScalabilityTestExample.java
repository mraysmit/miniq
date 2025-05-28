package miniq.client.examples;

import miniq.client.api.MessageConsumer;
import miniq.client.api.MessageProducer;
import miniq.client.impl.SimpleMessageConsumer;
import miniq.client.impl.SimpleMessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating scalability testing of MiniQ with different configurations.
 * This example runs multiple tests with increasing numbers of producers and consumers,
 * and reports various stuff on the performance of each configuration.
 */
public class ScalabilityTestExample {
    // Test configurations
    private static final int[] PRODUCER_COUNTS = {1, 2, 5, 10, 20};
    private static final int[] CONSUMER_COUNTS = {1, 2, 5, 10, 20};
    private static final int MESSAGES_PER_PRODUCER = 50;
    private static final int TEST_DURATION_SECONDS = 10;

    public static void main(String[] args) throws SQLException {
        System.out.println("Starting MiniQ Scalability Test");
        System.out.println("==============================");

        // Parse command line arguments
        int messagesPerProducer = args.length > 0 ? Integer.parseInt(args[0]) : MESSAGES_PER_PRODUCER;
        int testDurationSeconds = args.length > 1 ? Integer.parseInt(args[1]) : TEST_DURATION_SECONDS;

        System.out.println("Messages per producer: " + messagesPerProducer);
        System.out.println("Test duration (seconds): " + testDurationSeconds);
        System.out.println();

        // Print header for results table
        System.out.println("| Producers | Consumers | Total Messages | Throughput (msgs/sec) | Avg Latency (ms) |");
        System.out.println("|-----------|-----------|----------------|------------------------|------------------|");

        // Run tests with different configurations
        for (int producerCount : PRODUCER_COUNTS) {
            for (int consumerCount : CONSUMER_COUNTS) {
                // Skip configurations with too many threads to avoid resource issues
                if (producerCount + consumerCount > 30) {
                    continue;
                }

                try {
                    TestResult result = runTest(producerCount, consumerCount, messagesPerProducer, testDurationSeconds);

                    // Print results in table format
                    System.out.printf("| %-9d | %-9d | %-14d | %-22.2f | %-16.2f |%n",
                            producerCount,
                            consumerCount,
                            result.totalMessages,
                            result.throughput,
                            result.avgLatency);

                    // Give the system some time to recover between tests
                    Thread.sleep(1000);
                } catch (Exception e) {
                    System.err.println("Error running test with " + producerCount + " producers and " + 
                            consumerCount + " consumers: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    private static TestResult runTest(int producerCount, int consumerCount, int messagesPerProducer, int testDurationSeconds) 
            throws SQLException, InterruptedException, ExecutionException {
        // Create a MiniQ instance for this test
        QConfig config = new QConfig.Builder()
                .DbName("scalability_test_" + producerCount + "_" + consumerCount)
                .QueueName("messages")
                .QueueMaxSize(producerCount * messagesPerProducer * 2) // Ensure queue is large enough
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        MiniQ miniQ = new MiniQ(config);

        // Metrics
        AtomicInteger messagesProduced = new AtomicInteger(0);
        AtomicInteger messagesConsumed = new AtomicInteger(0);
        ConcurrentHashMap<String, Long> messageTimestamps = new ConcurrentHashMap<>();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try {
            // Create thread pools
            ExecutorService producerExecutor = Executors.newFixedThreadPool(producerCount);
            ExecutorService consumerExecutor = Executors.newFixedThreadPool(consumerCount);

            // Create and start consumers first
            List<MessageConsumer> consumers = new ArrayList<>();
            for (int i = 0; i < consumerCount; i++) {
                final int consumerId = i;
                MessageConsumer consumer = new SimpleMessageConsumer(miniQ);
                consumers.add(consumer);

                // Register callback for messages
                consumer.onMessage(message -> {
                    processMessage(message, consumerId, messagesConsumed, messageTimestamps, latencies);
                });
            }

            // Give consumers a chance to start polling
            System.out.println("Waiting for consumers to start...");
            Thread.sleep(1000);

            // Create and start producers
            List<CompletableFuture<Void>> producerFutures = new ArrayList<>();
            for (int i = 0; i < producerCount; i++) {
                final int producerId = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        runProducer(miniQ, producerId, messagesPerProducer, messagesProduced, messageTimestamps);
                    } catch (Exception e) {
                        System.err.println("Producer " + producerId + " error: " + e.getMessage());
                    }
                }, producerExecutor);
                producerFutures.add(future);
            }

            // Start the test timer
            long startTime = System.currentTimeMillis();

            // Wait for all producers to finish or for the test duration to expire
            CompletableFuture<Void> allProducers = CompletableFuture.allOf(
                    producerFutures.toArray(new CompletableFuture[0]));

            try {
                allProducers.get(testDurationSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // Test duration expired, which is expected
            }

            // Wait a bit more for consumers to process remaining messages
            Thread.sleep(2000);

            // Calculate test duration
            long endTime = System.currentTimeMillis();
            long testDurationMs = endTime - startTime;

            // Calculate metrics
            int totalMessages = messagesConsumed.get();
            double throughput = (totalMessages * 1000.0) / testDurationMs; // messages per second
            double avgLatency = latencies.isEmpty() ? 0 : 
                    latencies.stream().mapToLong(Long::longValue).average().orElse(0);

            // Shutdown executors
            producerExecutor.shutdownNow();
            consumerExecutor.shutdownNow();

            // Close consumers
            for (MessageConsumer consumer : consumers) {
                consumer.close();
            }

            return new TestResult(totalMessages, throughput, avgLatency);

        } finally {
            // Close the MiniQ instance
            miniQ.close();
        }
    }

    private static void runProducer(MiniQ miniQ, int producerId, int messageCount, 
            AtomicInteger messagesProduced, ConcurrentHashMap<String, Long> messageTimestamps) 
            throws ExecutionException, InterruptedException {
        MessageProducer producer = new SimpleMessageProducer(miniQ);

        try {
            for (int i = 0; i < messageCount; i++) {
                String messageData = "Message " + i + " from Producer " + producerId;
                String topic = "producer." + producerId;

                // Record timestamp before sending
                String messageKey = producerId + "-" + i;
                messageTimestamps.put(messageKey, System.currentTimeMillis());

                try {
                    // Send message
                    CompletableFuture<String> future = producer.sendMessage(messageData, topic);
                    future.get();

                    // Update metrics
                    messagesProduced.incrementAndGet();
                } catch (ExecutionException e) {
                    // If the queue is full, wait a bit and try again
                    if (e.getCause() instanceof RuntimeException && e.getCause().getMessage().contains("Failed to send message")) {
                        System.err.println("Producer " + producerId + " waiting for queue space...");
                        Thread.sleep(100); // Wait a bit before retrying
                        i--; // Retry this message
                        continue;
                    }
                    throw e; // Re-throw other exceptions
                }
            }
        } finally {
            producer.close();
        }
    }

    private static void processMessage(Message message, int consumerId, 
            AtomicInteger messagesConsumed, ConcurrentHashMap<String, Long> messageTimestamps,
            List<Long> latencies) {
        try {
            // Extract producer ID and message number from the data
            String data = message.data();
            String[] parts = data.split(" ");
            int messageNum = Integer.parseInt(parts[1]);
            int producerId = Integer.parseInt(parts[4]);

            // Calculate latency
            String messageKey = producerId + "-" + messageNum;
            Long sendTime = messageTimestamps.remove(messageKey);
            if (sendTime != null) {
                long latency = System.currentTimeMillis() - sendTime;
                latencies.add(latency);
            }

            // Update metrics
            messagesConsumed.incrementAndGet();
        } catch (Exception e) {
            // Handle parsing errors gracefully
            System.err.println("Error processing message: " + e.getMessage());
        }
    }

    private static class TestResult {
        final int totalMessages;
        final double throughput;
        final double avgLatency;

        TestResult(int totalMessages, double throughput, double avgLatency) {
            this.totalMessages = totalMessages;
            this.throughput = throughput;
            this.avgLatency = avgLatency;
        }
    }
}
