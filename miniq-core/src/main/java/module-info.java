module miniq.core {
    // Export your main packages
    exports miniq.core;
    exports miniq.config;
    exports miniq.db;
    exports miniq.core.model;
    exports miniq.core.utils;

    // Required modules from dependencies
    requires java.sql;
    requires com.fasterxml.uuid;
    requires org.slf4j;


    // JUnit is not required at runtime; no need to require for main module
}