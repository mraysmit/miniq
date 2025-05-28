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
import java.util.function.Consumer;

/**
 * Example demonstrating multiple concurrent producers and consumers with MiniQ.
 * This example validates concurrency and scalability by:
 * 1. Creating multiple producer threads, each sending a configurable number of messages
 * 2. Creating multiple consumer threads, each processing messages
 * 3. Tracking metrics like throughput and latency
 */
public class ConcurrentExample {
    // Configurable parameters
    private static final int NUM_PRODUCERS = 5;
    private static final int NUM_CONSUMERS = 3;
    private static final int MESSAGES_PER_PRODUCER = 100;
    private static final int PRODUCER_THREAD_POOL_SIZE = 10;
    private static final int CONSUMER_THREAD_POOL_SIZE = 6;
    
    // Metrics
    private static final AtomicInteger messagesProduced = new AtomicInteger(0);
    private static final AtomicInteger messagesConsumed = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, Long> messageTimestamps = new ConcurrentHashMap<>();
    private static final List<Long> latencies = new CopyOnWriteArrayList<>();
    
    public static void main(String[] args) throws SQLException, InterruptedException {
        // Parse command line arguments to override defaults
        int numProducers = getIntArg(args, 0, NUM_PRODUCERS);
        int numConsumers = getIntArg(args, 1, NUM_CONSUMERS);
        int messagesPerProducer = getIntArg(args, 2, MESSAGES_PER_PRODUCER);
        int producerThreadPoolSize = getIntArg(args, 3, PRODUCER_THREAD_POOL_SIZE);
        int consumerThreadPoolSize = getIntArg(args, 4, CONSUMER_THREAD_POOL_SIZE);
        
        System.out.println("Starting concurrent example with:");
        System.out.println("  Producers: " + numProducers);
        System.out.println("  Consumers: " + numConsumers);
        System.out.println("  Messages per producer: " + messagesPerProducer);
        System.out.println("  Producer thread pool size: " + producerThreadPoolSize);
        System.out.println("  Consumer thread pool size: " + consumerThreadPoolSize);
        
        // Create a MiniQ instance
        QConfig config = new QConfig.Builder()
                .DbName("concurrent_example")
                .QueueName("messages")
                .QueueMaxSize(numProducers * messagesPerProducer * 2) // Ensure queue is large enough
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        MiniQ miniQ = new MiniQ(config);
        
        try {
            // Create thread pools for producers and consumers
            ExecutorService producerExecutor = Executors.newFixedThreadPool(producerThreadPoolSize);
            ExecutorService consumerExecutor = Executors.newFixedThreadPool(consumerThreadPoolSize);
            
            // Create and start producers
            List<CompletableFuture<Void>> producerFutures = new ArrayList<>();
            for (int i = 0; i < numProducers; i++) {
                final int producerId = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        runProducer(miniQ, producerId, messagesPerProducer);
                    } catch (Exception e) {
                        System.err.println("Producer " + producerId + " error: " + e.getMessage());
                        e.printStackTrace();
                    }
                }, producerExecutor);
                producerFutures.add(future);
            }
            
            // Create and start consumers
            List<MessageConsumer> consumers = new ArrayList<>();
            CountDownLatch consumerLatch = new CountDownLatch(numProducers * messagesPerProducer);
            
            for (int i = 0; i < numConsumers; i++) {
                final int consumerId = i;
                MessageConsumer consumer = new SimpleMessageConsumer(miniQ);
                consumers.add(consumer);
                
                // Register callback for messages
                consumer.onMessage(message -> {
                    processMessage(message, consumerId);
                    consumerLatch.countDown();
                });
            }
            
            // Wait for all producers to finish
            CompletableFuture<Void> allProducers = CompletableFuture.allOf(
                    producerFutures.toArray(new CompletableFuture[0]));
            allProducers.join();
            
            System.out.println("All producers finished. Total messages produced: " + messagesProduced.get());
            
            // Wait for all messages to be consumed (with timeout)
            boolean allConsumed = consumerLatch.await(30, TimeUnit.SECONDS);
            
            if (allConsumed) {
                System.out.println("All messages consumed. Total messages consumed: " + messagesConsumed.get());
            } else {
                System.out.println("Timeout waiting for messages to be consumed. Messages consumed: " + 
                        messagesConsumed.get() + " out of " + messagesProduced.get());
            }
            
            // Print metrics
            printMetrics();
            
            // Shutdown executors
            producerExecutor.shutdown();
            consumerExecutor.shutdown();
            
            // Close consumers
            for (MessageConsumer consumer : consumers) {
                consumer.close();
            }
            
        } finally {
            // Close the MiniQ instance
            miniQ.close();
        }
    }
    
    private static void runProducer(MiniQ miniQ, int producerId, int messageCount) throws ExecutionException, InterruptedException {
        MessageProducer producer = new SimpleMessageProducer(miniQ);
        
        try {
            for (int i = 0; i < messageCount; i++) {
                String messageData = "Message " + i + " from Producer " + producerId;
                String topic = "producer." + producerId;
                
                // Record timestamp before sending
                String messageKey = producerId + "-" + i;
                messageTimestamps.put(messageKey, System.currentTimeMillis());
                
                // Send message
                CompletableFuture<String> future = producer.sendMessage(messageData, topic);
                String messageId = future.get();
                
                // Update metrics
                messagesProduced.incrementAndGet();
                
                if (i % 10 == 0) {
                    System.out.println("Producer " + producerId + " sent " + i + " messages");
                }
            }
        } finally {
            producer.close();
        }
    }
    
    private static void processMessage(Message message, int consumerId) {
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
        
        // Simulate some processing time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private static void printMetrics() {
        System.out.println("\nPerformance Metrics:");
        System.out.println("-------------------");
        System.out.println("Total messages produced: " + messagesProduced.get());
        System.out.println("Total messages consumed: " + messagesConsumed.get());
        
        if (!latencies.isEmpty()) {
            // Calculate average latency
            double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            
            // Calculate min and max latency
            long minLatency = latencies.stream().min(Long::compare).orElse(0L);
            long maxLatency = latencies.stream().max(Long::compare).orElse(0L);
            
            System.out.println("Average message latency: " + String.format("%.2f", avgLatency) + " ms");
            System.out.println("Minimum message latency: " + minLatency + " ms");
            System.out.println("Maximum message latency: " + maxLatency + " ms");
        }
    }
    
    private static int getIntArg(String[] args, int index, int defaultValue) {
        if (args.length > index) {
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}