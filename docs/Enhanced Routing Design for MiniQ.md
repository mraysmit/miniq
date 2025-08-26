
# Enhanced Routing Design for MiniQ

## Current Routing System Analysis

The current MiniQ implementation uses a simple string-based routing pattern where:

1. Messages are published with a topic (e.g., "orders.food")
2. Consumers can subscribe using patterns with basic wildcards (e.g., "orders.*")
3. Pattern matching is implemented using SQL LIKE queries
4. The implementation supports only simple wildcard matching with "*" representing any sequence of characters

## Proposed Enhanced Routing Design

I propose implementing a hierarchical multi-part routing key system with advanced wildcard support, similar to AMQP-style topic exchanges but tailored for SQLite.

### 1. Multi-part Routing Key Structure

Define a structured routing key format with dot-separated segments:
```
segment1.segment2.segment3.segment4
```

Examples:
- `orders.electronics.laptops.premium`
- `notifications.user.123.email`
- `logs.system.error.database`

### 2. Enhanced Wildcard Support

Implement two types of wildcards:

1. **Single-segment wildcard (`*`)**: Matches exactly one segment
   - `orders.*.laptops` matches `orders.electronics.laptops` but not `orders.electronics.computers.laptops`

2. **Multi-segment wildcard (`#`)**: Matches zero or more segments
   - `orders.#` matches `orders.electronics`, `orders.electronics.laptops`, etc.
   - `orders.electronics.#.premium` matches `orders.electronics.laptops.premium` and `orders.electronics.computers.accessories.premium`

### 3. Implementation Approach

#### Database Schema Enhancement

Add a new table to store parsed routing segments for efficient matching:

```sql
CREATE TABLE routing_segments (
    message_id TEXT NOT NULL,
    segment_position INTEGER NOT NULL,
    segment_value TEXT NOT NULL,
    PRIMARY KEY (message_id, segment_position),
    FOREIGN KEY (message_id) REFERENCES queue_table(message_id) ON DELETE CASCADE
);
```

#### Message Publishing

When a message is published with a routing key:

1. Store the full routing key in the existing topic field
2. Parse the routing key into segments
3. Store each segment with its position in the routing_segments table

```java
public Message putMessage(String data, String routingKey) {
    // Create message in main queue table
    Message message = createMessage(data, routingKey);
    
    // Parse and store routing segments
    String[] segments = routingKey.split("\\.");
    for (int i = 0; i < segments.length; i++) {
        storeRoutingSegment(message.messageId(), i, segments[i]);
    }
    
    return message;
}
```

#### Pattern Matching Logic

Implement a pattern matcher that supports both wildcard types:

```java
public Message popWithRoutingPattern(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
        return pop(); // Default behavior
    }
    
    if (!pattern.contains("*") && !pattern.contains("#")) {
        // Exact match
        return popWithExactRoutingKey(pattern);
    }
    
    // Parse pattern into segments
    String[] patternSegments = pattern.split("\\.");
    
    // Build SQL query based on pattern segments
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT m.* FROM queue_table m WHERE m.status = ? AND EXISTS (");
    
    // Build subquery for pattern matching
    buildPatternMatchingQuery(sql, patternSegments);
    
    sql.append(") ORDER BY m.in_time LIMIT 1");
    
    // Execute query with appropriate parameters
    // ...
}
```

### 4. Optimizations

1. **Indexing**: Create indexes on the routing_segments table for efficient lookups
   ```sql
   CREATE INDEX idx_routing_segments_value ON routing_segments(segment_value);
   CREATE INDEX idx_routing_segments_position ON routing_segments(segment_position);
   ```

2. **Caching**: Cache frequently used routing patterns and their compiled SQL queries

3. **Batch operations**: When publishing multiple messages with the same routing pattern, batch the segment insertions

### 5. API Enhancements

Extend the existing API to support the new routing capabilities:

```java
// Producer API
CompletableFuture<String> sendMessage(String data, String[] routingKeySegments);

// Consumer API
void onMessage(String[] routingKeyPattern, Consumer<Message> callback);
```

### 6. Example Usage

```java
// Publishing with multi-part routing key
producer.sendMessage(orderData, "orders.electronics.laptops.premium");

// Subscribing with single-segment wildcards
consumer.onMessage("orders.*.laptops.*", message -> {
    // Handle all laptop orders from any category and any price range
});

// Subscribing with multi-segment wildcards
consumer.onMessage("orders.electronics.#", message -> {
    // Handle all electronics orders regardless of subcategories
});

// Complex pattern
consumer.onMessage("orders.#.premium", message -> {
    // Handle all premium orders from any category
});
```

## Implementation Phases

1. **Phase 1**: Extend the database schema and core routing logic
2. **Phase 2**: Implement the enhanced pattern matching algorithm
3. **Phase 3**: Update the client API and provide migration utilities
4. **Phase 4**: Add performance optimizations and caching
5. **Phase 5**: Create comprehensive documentation and examples

This design maintains backward compatibility while significantly enhancing the routing capabilities of the MiniQ system, allowing for more sophisticated message filtering and routing scenarios.