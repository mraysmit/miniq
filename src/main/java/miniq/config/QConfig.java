package miniq.config;

public record QConfig(String dbName, String queueName, Integer queueMaxSize, Integer sqliteCacheSizeBytes, Boolean createDb, Boolean createQueue) {

    public static class Builder {
        private String dbName;
        private String queueName;
        private Integer queueMaxSize;
        private Integer sqliteCacheSizeBytes;
        private Boolean createDb;
        private Boolean createQueue;

        public Builder DbName(String dbName) {
            this.dbName = dbName + ".db";
            return this;
        }

        public Builder QueueName(String queueName) {
            this.queueName = queueName != null ? queueName : "queue";
            return this;
        }

        public Builder QueueMaxSize(Integer queueMaxSize) {
            this.queueMaxSize = queueMaxSize != null ? queueMaxSize : Integer.MAX_VALUE;
            return this;
        }

        public Builder SqliteCacheSizeBytes(Integer sqliteCacheSizeBytes) {
            this.sqliteCacheSizeBytes = sqliteCacheSizeBytes != null ? sqliteCacheSizeBytes : 256000;
            return this;
        }

        public Builder CreateDb(Boolean createDb) {
            this.createDb = createDb;
            return this;
        }

        public Builder CreateQueue(Boolean createQueue) {
            this.createQueue = createQueue;
            return this;
        }

        public QConfig build() {
            return new QConfig(dbName, queueName, queueMaxSize, sqliteCacheSizeBytes, createDb, createQueue);
        }

    }





}