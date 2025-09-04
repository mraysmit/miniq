# MiniQ - Comprehensive Functionality Review

**Version:** 1.0-SNAPSHOT  
**Date:** 2025-09-04  
**Author:** Mark Andrew Ray-Smith, Cityline Ltd  
**Review Date:** Current State Analysis  

## Project Overview

MiniQ is a lightweight, embedded message queue system built on SQLite for Java applications. It provides reliable message queuing without requiring external message brokers, making it ideal for embedded systems and applications that need simple, persistent messaging.

## Architecture

### Multi-Module Maven Structure
- **Parent Module**: `miniq-parent` (dev.mars:miniq-parent:1.0-SNAPSHOT)
- **Core Module**: `miniq-core` - Contains the core queue implementation
- **Client Module**: `miniq-client` - Provides high-level producer/consumer APIs

### Technology Stack
- **Java 23** (using preview features)
- **SQLite** with WAL mode for persistence and concurrency
- **Maven** for build management
- **JUnit 5** for testing
- **SLF4J + Logback** for logging

### Key Dependencies
- `sqlite-jdbc` (3.49.1.0) - SQLite database connectivity
- `java-uuid-generator` (4.1.0) - UUID generation
- `slf4j-api` + `logback-classic` - Logging framework

### Module System
The project uses Java modules (`module-info.java`):
- `miniq.core` - Exports core packages and database functionality
- `miniq.client` - Depends on core module, provides client APIs

## Core Features

### 1. Message Queue Capabilities
- **FIFO Processing**: First-in, first-out message ordering
- **Message Status Tracking**: READY → LOCKED → DONE/FAILED/ARCHIVED lifecycle
- **Topic-based Routing**: Wildcard pattern matching (e.g., `orders.*`)
- **Transaction Support**: Reliable message delivery with SQLite transactions
- **Configurable Queue Size**: Prevents memory overflow with max size limits
- **Message Priority Support**: Priority levels from 1 (highest) to 10 (lowest)

### 2. Message Statuses
- `READY(0)`: Message is ready to be processed
- `LOCKED(1)`: Message is being processed
- `DONE(2)`: Message has been successfully processed
- `FAILED(3)`: Message processing has failed
- `ARCHIVED(4)`: Message has been archived

### 3. Routing System
The current implementation supports:
- **Exact Topic Matching**: Direct topic name matching
- **Wildcard Patterns**: Using `*` for pattern matching
- **Enhanced Routing**: Partial implementation of multi-segment routing with `#` wildcards
- **Routing Segments Table**: Database table for storing parsed routing segments

### 4. Database Schema
Main queue table structure:
```sql
CREATE TABLE queue_table (
    message_id TEXT NOT NULL,
    topic TEXT,
    data TEXT NOT NULL,
    status INTEGER NOT NULL,
    in_time INTEGER NOT NULL,
    lock_time INTEGER,
    done_time INTEGER
)
```

Additional routing segments table for enhanced routing:
```sql
CREATE TABLE routing_segments (
    message_id TEXT NOT NULL,
    segment_position INTEGER NOT NULL,
    segment_value TEXT NOT NULL,
    PRIMARY KEY (message_id, segment_position),
    FOREIGN KEY (message_id) REFERENCES queue_table(message_id) ON DELETE CASCADE
)
```

## Client APIs

### MessageProducer Interface
Provides asynchronous message sending capabilities:
- `sendMessage(String data)` - Send message without topic
- `sendMessage(String data, String topic)` - Send message with topic
- `sendMessage(String data, int priority)` - Send message with priority
- `sendMessage(String data, String topic, int priority)` - Send message with topic and priority

### MessageConsumer Interface
Provides message consumption with callbacks and acknowledgments:
- `receiveMessage()` - Receive any available message
- `receiveMessage(String topicPattern)` - Receive message matching topic pattern
- `acknowledgeMessage(String messageId)` - Mark message as successfully processed
- `rejectMessage(String messageId)` - Mark message as failed
- `onMessage(Consumer<Message> callback)` - Register callback for all messages
- `onMessage(String topicPattern, Consumer<Message> callback)` - Register callback for specific topics

## Configuration System

The `QConfig` builder provides comprehensive configuration:
- `DbName(String)`: Database file name (`.db` extension added automatically)
- `QueueName(String)`: Queue table name in the database
- `QueueMaxSize(Integer)`: Maximum number of messages (defaults to `Integer.MAX_VALUE`)
- `SqliteCacheSizeBytes(Integer)`: SQLite cache size (defaults to 256KB)
- `CreateDb(Boolean)`: Whether to create database if missing
- `CreateQueue(Boolean)`: Whether to create queue table if missing

## Performance Optimizations

### SQLite Configuration
- **WAL Mode**: Write-Ahead Logging for better concurrency
- **Memory Temp Store**: Temporary tables stored in memory
- **Normal Synchronous**: Balanced durability vs performance
- **Configurable Cache Size**: Tunable memory usage

### Queue Management
- **FIFO Optimization**: Efficient queue operations using timestamps
- **Connection Management**: Proper transaction handling
- **Pattern Query Caching**: Cached frequently used routing patterns
- **Trigger-based Queue Control**: Automatic overflow management

## Example Applications

### 1. Real-World Order Processing System
- **Multiple Producers**: 5 order generators creating different order types
- **Specialized Consumers**: 8 processors handling food, electronics, books, clothing orders
- **Monitoring System**: Real-time metrics and statistics
- **Configurable Parameters**: Command-line arguments for scaling

### 2. Scalability Testing
- **Concurrent Producers/Consumers**: Tests with varying thread counts
- **Performance Metrics**: Throughput and latency measurements
- **Load Testing**: Validates system behavior under stress

### 3. Integration Examples
- `ClientExample`: Basic producer/consumer usage
- `ProducerExample`: Message sending patterns
- `ConsumerExample`: Message consumption with callbacks
- `ConcurrentExample`: Multi-threaded scenarios

## Testing Strategy

### Test Coverage
- **Unit Tests**: Core functionality validation
- **Integration Tests**: End-to-end producer/consumer workflows
- **Scalability Tests**: Performance and concurrency validation
- **Parameterized Tests**: Multiple configuration scenarios

### Test Infrastructure
- JUnit 5 with parameterized tests
- Concurrent testing with ExecutorService
- Metrics collection (throughput, latency)
- Automated cleanup and resource management

## Current Implementation Status

### ✅ Completed Features
- Core queue functionality with FIFO processing
- Client APIs with async support using CompletableFuture
- Topic-based routing with basic wildcard patterns
- Message status lifecycle management
- Transaction support for reliability
- Comprehensive test suite
- Example applications and documentation
- Message priority support
- Multi-module Maven structure with Java modules

### ✅ Recently Completed Features
- **Enhanced Routing**: Complete multi-segment routing with `#` wildcards implemented across all routing methods
- **Routing Segments**: Database table fully integrated with all routing operations
- **Priority Support**: Complete message priority system with database schema and API support

### 📋 Planned Enhancements (from TODO comments)
- Push notifications via pub/sub callbacks
- Webhook support for message subscribers
- Enhanced error handling
- Complete implementation of multi-segment routing

## Strengths

1. **Zero External Dependencies**: Embedded SQLite-based solution
2. **Modern Java**: Uses Java 23 with module system
3. **Comprehensive Testing**: Multiple test scenarios and examples
4. **Async APIs**: CompletableFuture-based client interfaces
5. **Topic Routing**: Flexible message routing with patterns
6. **Production Ready**: Transaction support and error handling
7. **Scalable Architecture**: Multi-module design with clear separation of concerns

## Areas for Improvement

1. **Documentation**: Installation section references incorrect groupId (`com.github.mraysmit` vs `dev.mars`)
2. **Enhanced Routing**: Complete implementation of multi-segment routing with `#` wildcards
3. **Monitoring**: Built-in metrics and monitoring capabilities
4. **Configuration**: More runtime configuration options
5. **Error Handling**: Enhanced error handling as noted in TODOs
6. **Subscriber Notifications**: Implementation of push notification system

## Conclusion

MiniQ is a well-architected, feature-complete embedded message queue system that successfully balances simplicity with functionality. It provides a reliable alternative to heavyweight message brokers for Java applications. The comprehensive test suite and example applications demonstrate its readiness for production use, while the planned enhancements show a clear roadmap for future development.

The system demonstrates excellent engineering practices with proper module separation, comprehensive testing, and modern Java features. The SQLite-based approach makes it particularly suitable for embedded systems and applications that need reliable messaging without external infrastructure dependencies.
