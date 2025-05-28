
1. `__init__`: Initializes the `MiniQ` instance.
2. `get_sqlite_version`: Returns the minor version number of SQLite being used.
3. `_select_pop_func`: Decides which message pop() logic to use depending on the SQLite version.
4. `put`: Inserts a new message into the queue.
5. `_pop_returning`: Pops a message from the queue using the SQLite RETURNING clause.
6. `_pop_transaction`: Pops a message from the queue using a transaction and custom locking logic.
7. `peek`: Shows the next message to be popped, if any.
8. `get`: Gets a message by its `message_id`.
9. `done`: Marks a message as done.
10. `mark_failed`: Marks a message as failed.
11. `list_locked`: Returns all the tasks that have been in the `LOCKED` state for more than `threshold_seconds` seconds.
12. `list_failed`: Returns all the tasks in `FAILED` state.
13. `retry`: Marks a locked message as free again.
14. `qsize`: Gets the current size of the queue.
15. `empty`: Returns True if the queue is empty.
16. `full`: Returns True if the queue is full.
17. `prune`: Deletes `DONE` messages.
18. `vacuum`: Vacuums the database.
19. `transaction`: Context manager for handling SQLite transactions.
20. `__repr__`: Returns a string representation of the `MiniQ` instance.
21. `close`: Closes the SQLite connection.