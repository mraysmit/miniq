module miniq.client {
    // Export your main packages
    exports miniq.client.api;
    exports miniq.client.impl;
    exports miniq.client.examples;

    // Required modules from dependencies
    requires miniq.core;
    requires java.sql;
    requires org.slf4j;
    requires java.base;

    // Open packages for testing
    opens miniq.client.impl to org.junit.platform.commons;

    // JUnit is not required at runtime; no need to require for main module
}
