package miniq.client.examples;

import miniq.client.api.MessageConsumer;
import miniq.client.api.MessageProducer;
import miniq.client.impl.SimpleMessageConsumer;
import miniq.client.impl.SimpleMessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Example demonstrating a realistic use case with MiniQ in an order processing system.
 * This example simulates:
 * 1. Multiple order generators (producers) creating different types of orders
 * 2. Multiple specialized processors (consumers) handling different order types
 * 3. A monitoring system tracking the flow of orders through the system
 */
public class RealWorldExample {
    // Configurable parameters
    private static final int NUM_ORDER_GENERATORS = 5;
    private static final int NUM_ORDER_PROCESSORS = 8;
    private static final int ORDERS_PER_GENERATOR = 50;
    private static final int SIMULATION_DURATION_SECONDS = 30;
    
    // Order types and their probabilities
    private static final String[] ORDER_TYPES = {"food", "electronics", "clothing", "books", "furniture"};
    private static final double[] ORDER_TYPE_PROBABILITIES = {0.4, 0.25, 0.2, 0.1, 0.05};
    
    // Metrics
    private static final Map<String, AtomicInteger> ordersGeneratedByType = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> ordersProcessedByType = new ConcurrentHashMap<>();
    private static final AtomicInteger totalOrdersGenerated = new AtomicInteger(0);
    private static final AtomicInteger totalOrdersProcessed = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, Long> orderTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, List<Long>> processingTimesByType = new ConcurrentHashMap<>();
    
    static {
        // Initialize metrics counters
        for (String type : ORDER_TYPES) {
            ordersGeneratedByType.put(type, new AtomicInteger(0));
            ordersProcessedByType.put(type, new AtomicInteger(0));
            processingTimesByType.put(type, new CopyOnWriteArrayList<>());
        }
    }
    
    public static void main(String[] args) throws SQLException, InterruptedException {
        // Parse command line arguments
        int numGenerators = args.length > 0 ? Integer.parseInt(args[0]) : NUM_ORDER_GENERATORS;
        int numProcessors = args.length > 1 ? Integer.parseInt(args[1]) : NUM_ORDER_PROCESSORS;
        int ordersPerGenerator = args.length > 2 ? Integer.parseInt(args[2]) : ORDERS_PER_GENERATOR;
        int simulationDuration = args.length > 3 ? Integer.parseInt(args[3]) : SIMULATION_DURATION_SECONDS;
        
        System.out.println("Starting Order Processing Simulation");
        System.out.println("==================================");
        System.out.println("Order Generators: " + numGenerators);
        System.out.println("Order Processors: " + numProcessors);
        System.out.println("Orders per Generator: " + ordersPerGenerator);
        System.out.println("Simulation Duration: " + simulationDuration + " seconds");
        System.out.println();
        
        // Create a MiniQ instance
        QConfig config = new QConfig.Builder()
                .DbName("order_processing")
                .QueueName("orders")
                .QueueMaxSize(numGenerators * ordersPerGenerator * 2) // Ensure queue is large enough
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        MiniQ miniQ = new MiniQ(config);
        
        try {
            // Create thread pools
            ExecutorService generatorExecutor = Executors.newFixedThreadPool(numGenerators);
            ExecutorService processorExecutor = Executors.newFixedThreadPool(numProcessors);
            ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
            
            // Start the monitoring system
            startMonitoringSystem(monitorExecutor);
            
            // Create and start order generators (producers)
            List<CompletableFuture<Void>> generatorFutures = new ArrayList<>();
            for (int i = 0; i < numGenerators; i++) {
                final int generatorId = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        runOrderGenerator(miniQ, generatorId, ordersPerGenerator);
                    } catch (Exception e) {
                        System.err.println("Order Generator " + generatorId + " error: " + e.getMessage());
                        e.printStackTrace();
                    }
                }, generatorExecutor);
                generatorFutures.add(future);
            }
            
            // Create and start order processors (consumers)
            List<MessageConsumer> processors = new ArrayList<>();
            
            // Create specialized processors for each order type
            for (String orderType : ORDER_TYPES) {
                // Create 1-2 dedicated processors for each order type
                int dedicatedProcessors = orderType.equals("food") ? 2 : 1; // More processors for food orders
                
                for (int i = 0; i < dedicatedProcessors; i++) {
                    MessageConsumer processor = new SimpleMessageConsumer(miniQ);
                    processors.add(processor);
                    
                    // Register callback for specific order type
                    processor.onMessage("orders." + orderType, message -> {
                        processOrder(message, orderType);
                    });
                }
            }
            
            // Create general processors for any order type
            int generalProcessors = numProcessors - processors.size();
            for (int i = 0; i < generalProcessors; i++) {
                MessageConsumer processor = new SimpleMessageConsumer(miniQ);
                processors.add(processor);
                
                // Register callback for any order
                processor.onMessage(message -> {
                    String orderType = extractOrderType(message);
                    processOrder(message, orderType);
                });
            }
            
            // Wait for all generators to finish or for the simulation duration to expire
            CompletableFuture<Void> allGenerators = CompletableFuture.allOf(
                    generatorFutures.toArray(new CompletableFuture[0]));
            
            try {
                allGenerators.get(simulationDuration, TimeUnit.SECONDS);
                System.out.println("All order generators completed their work.");
            } catch (TimeoutException e) {
                System.out.println("Simulation duration reached. Stopping order generators.");
            } catch (ExecutionException e) {
                System.err.println("Error in order generators: " + e.getMessage());
            }
            
            // Wait a bit more for processors to finish processing remaining orders
            System.out.println("Waiting for order processors to complete...");
            Thread.sleep(5000);
            
            // Print final statistics
            printFinalStatistics();
            
            // Shutdown executors
            generatorExecutor.shutdownNow();
            processorExecutor.shutdownNow();
            monitorExecutor.shutdownNow();
            
            // Close processors
            for (MessageConsumer processor : processors) {
                processor.close();
            }
            
        } finally {
            // Close the MiniQ instance
            miniQ.close();
        }
    }
    
    private static void runOrderGenerator(MiniQ miniQ, int generatorId, int orderCount) 
            throws ExecutionException, InterruptedException {
        MessageProducer producer = new SimpleMessageProducer(miniQ);
        Random random = new Random();
        
        try {
            for (int i = 0; i < orderCount; i++) {
                // Select an order type based on probabilities
                String orderType = selectOrderType(random);
                
                // Create order data
                String orderId = UUID.randomUUID().toString().substring(0, 8);
                double amount = 10 + random.nextDouble() * 990; // $10-$1000
                String orderData = String.format(
                        "{\"orderId\":\"%s\",\"type\":\"%s\",\"amount\":%.2f,\"generator\":%d}",
                        orderId, orderType, amount, generatorId);
                
                // Record timestamp before sending
                orderTimestamps.put(orderId, System.currentTimeMillis());
                
                // Send order to the queue
                String topic = "orders." + orderType;
                CompletableFuture<String> future = producer.sendMessage(orderData, topic);
                future.get();
                
                // Update metrics
                totalOrdersGenerated.incrementAndGet();
                ordersGeneratedByType.get(orderType).incrementAndGet();
                
                // Add some randomness to order generation timing
                Thread.sleep(random.nextInt(100));
            }
        } finally {
            producer.close();
        }
    }
    
    private static String selectOrderType(Random random) {
        double value = random.nextDouble();
        double cumulativeProbability = 0.0;
        
        for (int i = 0; i < ORDER_TYPES.length; i++) {
            cumulativeProbability += ORDER_TYPE_PROBABILITIES[i];
            if (value <= cumulativeProbability) {
                return ORDER_TYPES[i];
            }
        }
        
        return ORDER_TYPES[0]; // Default to first type if something goes wrong
    }
    
    private static void processOrder(Message message, String orderType) {
        try {
            // Parse the order data
            String data = message.data();
            Map<String, Object> order = parseOrderData(data);
            String orderId = (String) order.get("orderId");
            
            // Calculate processing time
            Long startTime = orderTimestamps.remove(orderId);
            if (startTime != null) {
                long processingTime = System.currentTimeMillis() - startTime;
                processingTimesByType.get(orderType).add(processingTime);
            }
            
            // Simulate processing time based on order type
            int processingDelay = getProcessingDelayForOrderType(orderType);
            Thread.sleep(processingDelay);
            
            // Update metrics
            totalOrdersProcessed.incrementAndGet();
            ordersProcessedByType.get(orderType).incrementAndGet();
            
        } catch (Exception e) {
            System.err.println("Error processing order: " + e.getMessage());
        }
    }
    
    private static int getProcessingDelayForOrderType(String orderType) {
        // Different order types have different processing times
        switch (orderType) {
            case "food": return 50;  // Fast processing for food
            case "electronics": return 200;  // Electronics take longer
            case "furniture": return 300;  // Furniture takes the longest
            default: return 100;  // Default processing time
        }
    }
    
    private static String extractOrderType(Message message) {
        try {
            String data = message.data();
            Map<String, Object> order = parseOrderData(data);
            return (String) order.get("type");
        } catch (Exception e) {
            System.err.println("Error extracting order type: " + e.getMessage());
            return "unknown";
        }
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOrderData(String data) {
        // Simple parsing of JSON-like data
        // In a real application, use a proper JSON parser
        Map<String, Object> result = new HashMap<>();
        
        // Remove the curly braces
        data = data.substring(1, data.length() - 1);
        
        // Split by commas
        String[] pairs = data.split(",");
        
        for (String pair : pairs) {
            // Split by colon
            String[] keyValue = pair.split(":");
            
            // Clean up the key and value
            String key = keyValue[0].trim().replace("\"", "");
            String value = keyValue[1].trim();
            
            // Parse the value based on its format
            if (value.startsWith("\"") && value.endsWith("\"")) {
                // String value
                result.put(key, value.substring(1, value.length() - 1));
            } else if (value.contains(".")) {
                // Double value
                result.put(key, Double.parseDouble(value));
            } else {
                // Integer value
                result.put(key, Integer.parseInt(value));
            }
        }
        
        return result;
    }
    
    private static void startMonitoringSystem(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(() -> {
            System.out.println("\n--- Order Processing Status ---");
            System.out.println("Total Orders Generated: " + totalOrdersGenerated.get());
            System.out.println("Total Orders Processed: " + totalOrdersProcessed.get());
            System.out.println("Orders in Queue: " + 
                    (totalOrdersGenerated.get() - totalOrdersProcessed.get()));
            
            System.out.println("\nOrders by Type:");
            for (String type : ORDER_TYPES) {
                int generated = ordersGeneratedByType.get(type).get();
                int processed = ordersProcessedByType.get(type).get();
                System.out.printf("  %s: Generated=%d, Processed=%d, Pending=%d%n", 
                        type, generated, processed, generated - processed);
            }
            
            System.out.println("\nAverage Processing Times:");
            for (String type : ORDER_TYPES) {
                List<Long> times = processingTimesByType.get(type);
                if (!times.isEmpty()) {
                    double avgTime = times.stream().mapToLong(Long::longValue).average().orElse(0);
                    System.out.printf("  %s: %.2f ms%n", type, avgTime);
                }
            }
            
            System.out.println("-----------------------------");
        }, 2, 5, TimeUnit.SECONDS);
    }
    
    private static void printFinalStatistics() {
        System.out.println("\n=== Final Simulation Statistics ===");
        System.out.println("Total Orders Generated: " + totalOrdersGenerated.get());
        System.out.println("Total Orders Processed: " + totalOrdersProcessed.get());
        
        System.out.println("\nOrders by Type:");
        for (String type : ORDER_TYPES) {
            int generated = ordersGeneratedByType.get(type).get();
            int processed = ordersProcessedByType.get(type).get();
            System.out.printf("  %s: Generated=%d, Processed=%d (%.1f%%)%n", 
                    type, generated, processed, 
                    generated > 0 ? (processed * 100.0 / generated) : 0);
        }
        
        System.out.println("\nProcessing Time Statistics:");
        for (String type : ORDER_TYPES) {
            List<Long> times = processingTimesByType.get(type);
            if (!times.isEmpty()) {
                DoubleSummaryStatistics stats = times.stream()
                        .mapToDouble(Long::doubleValue)
                        .summaryStatistics();
                
                System.out.printf("  %s: Avg=%.2f ms, Min=%.0f ms, Max=%.0f ms, Count=%d%n", 
                        type, stats.getAverage(), stats.getMin(), stats.getMax(), stats.getCount());
            }
        }
        
        System.out.println("==================================");
    }
}