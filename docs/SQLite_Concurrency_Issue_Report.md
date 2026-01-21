# Investigation Report: SQLite Concurrency Issue in MiniQ

**Date:** January 21, 2026  
**Status:** Open  
**Affected Test:** `ClientIntegrationTest.testCallbackIntegration`

## Summary

The `ClientIntegrationTest.testCallbackIntegration` test fails with `SQLITE_BUSY` and `SQLITE_ERROR` exceptions due to concurrent database access from multiple threads sharing a single SQLite connection.

## Root Cause Analysis

### 1. Architecture Issue: Single Shared Connection

The test creates one `MiniQ` instance with a single SQLite `Connection` object:

```java
// ClientIntegrationTest.java lines 43-44
miniQ = new MiniQ(config);
producer = new SimpleMessageProducer(miniQ);
consumer = new SimpleMessageConsumer(miniQ);
```

Both producer and consumer share the same `MiniQ` instance and therefore the same database connection.

### 2. Multi-threaded Access

When `consumer.onMessage()` is called, it starts a background polling thread:
- `SimpleMessageConsumer.java` line 169-175 - Uses `scheduler.scheduleWithFixedDelay()` to poll for messages

Simultaneously, the producer sends messages from a different thread:
- `SimpleMessageProducer.java` line 37-44 - Uses `CompletableFuture.supplyAsync()` with its own executor

### 3. Transaction Conflicts

The `MiniQ.putMessage()` and `MiniQ.setStatus()` methods both manipulate `autoCommit` state on the shared connection:
- `MiniQ.java` line 96-99 - `putMessage()` sets `conn.setAutoCommit(false)`
- `MiniQ.java` line 796-799 - `setStatus()` also sets `conn.setAutoCommit(false)`

When these run concurrently:
1. Thread A (producer) starts a transaction: `setAutoCommit(false)`
2. Thread B (consumer) tries to start its own transaction: `setAutoCommit(false)` → **SQLITE_BUSY** or **cannot start a transaction within a transaction**

### 4. Existing Mitigation Attempts

The consumer code has a `synchronized (miniQ)` block at line 177 in `SimpleMessageConsumer.java`, but the producer has no such synchronization, creating a race condition.

## Error Sequence (from test output)

```
1. Consumer starts polling with scheduler.scheduleWithFixedDelay()
2. Producer calls miniQ.put() → begins transaction
3. Consumer tries miniQ.popWithRoutingPattern() or miniQ.setDone() → SQLITE_BUSY
4. Transaction state becomes corrupted → "cannot start a transaction within a transaction"
```

### Error Messages Observed

```
[SQLITE_BUSY] The database file is locked (cannot commit transaction - SQL statements in progress)
[SQLITE_ERROR] SQL error or missing database (cannot start a transaction within a transaction)
```

## Proposed Solutions

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| **A. Enable WAL mode** | Use SQLite Write-Ahead Logging | Simple; allows concurrent reads; minimal code changes | Still single-writer; doesn't fully solve write contention |
| **B. Synchronized access** | Add `synchronized` to all MiniQ database operations | Thread-safe; preserves single connection | Reduces throughput; potential deadlocks |
| **C. Connection per thread** | Each producer/consumer gets its own connection | True concurrency; best throughput | More resource usage; complexity |
| **D. Connection pooling** | Use a connection pool (HikariCP, etc.) | Scalable; industry standard | External dependency; more complex |

## Recommendation

**Implement Option A (WAL mode) + B (Synchronized access)** as immediate fixes:

1. **Enable WAL mode** in `MiniQ` initialization - allows readers while writing
2. **Add connection-level synchronization** in `MiniQ` for all database operations

This provides:
- Quick fix with minimal code changes
- Thread-safe operations
- Better concurrency than current state
- Foundation for future connection pooling if needed

## Implementation Details

### WAL Mode

Add to `MiniQ.Qinit()`:
```java
try (Statement stmt = conn.createStatement()) {
    stmt.execute("PRAGMA journal_mode=WAL");
    stmt.execute("PRAGMA busy_timeout=5000");
}
```

### Synchronized Access

Add a lock object and synchronize all database operations:
```java
private final Object dbLock = new Object();

public Message put(String data) throws SQLException {
    synchronized (dbLock) {
        return putMessage(data, null, Message.DEFAULT_PRIORITY);
    }
}
```

## References

- [SQLite WAL Mode](https://www.sqlite.org/wal.html)
- [SQLite Busy Handling](https://www.sqlite.org/lang_transaction.html)
- [Java synchronized keyword](https://docs.oracle.com/javase/tutorial/essential/concurrency/syncmeth.html)
