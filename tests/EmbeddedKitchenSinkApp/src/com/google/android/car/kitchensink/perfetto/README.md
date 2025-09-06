# Perfetto Controller and Perfetto Report Service

This document provides an overview of the `PerfettoController` and `PerfettoReportService` classes, which are designed to manage and report Perfetto traces within the Android ecosystem.

## PerfettoController

The `PerfettoController` class is a comprehensive toolkit for managing Perfetto field traces. It simplifies tasks such as pushing trace configurations, triggering trace collection, querying active configurations, and cleaning up by removing configurations from `statsd`.

### Key Features

- **Push Perfetto Trace Configurations**: Easily push default or custom Perfetto trace configurations to `statsd`.
- **Trigger Trace Collection**: Programmatically trigger the collection of Perfetto traces.
- **Query Active Configuration**: Inspect the currently active Perfetto trace configuration.
- **Remove Configurations**: Cleanly remove Perfetto trace configurations from `statsd`.

## PerfettoReportService

The `PerfettoReportService` is a `JobService` that handles the processing of Perfetto trace reports. When a trace is completed, this service is triggered to handle the resulting trace data, which is provided as a file descriptor.

### Key Responsibilities

- **Trace Handling**: Receives the trace data and processes it.
- **File Management**: Copies the trace data to a file for later analysis.
- **Error Handling**: Manages errors that may occur during trace processing.

## Scalable Hash Generation

For more complex scenarios where you need to manage numerous configurations, alarms, and subscriptions, a scalable hash generation approach is recommended. The `HashGenerator` class is an example of how to create unique, deterministic hashes for various configuration entities.

### HashGenerator Example

The `HashGenerator` class uses SHA-256 to generate long hash values from string names, namespaced by an entity type (e.g., config ID, alarm ID). It ensures uniqueness by handling collisions and uses a bidirectional map to cache and retrieve mappings between names and their corresponding hashes.

```java
/**
 * A generator for creating unique, deterministic hashes for configuration entities.
 *
 * <p>This class generates long hash values from string names, namespaced by an entity type
 * (e.g., config ID, alarm ID). It uses SHA-256 for hashing and ensures uniqueness by handling
 * collisions. A bidirectional map is used to cache and retrieve mappings between names and
 * their corresponding hashes, allowing for efficient lookups.
 */
private static class HashGenerator {
    private static final HashFunction HASH_FUNCTION = Hashing.sha256();
    private BiMap<String, Long> mNameToHash = HashBiMap.create();

    private static final int ENTITY_TYPE_CONFIG_ID = 0;
    private static final int ENTITY_TYPE_ALARM_ID = 1;
    private static final int ENTITY_TYPE_SUBSCRIPTION_ID = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ENTITY_TYPE"}, value = {
            ENTITY_TYPE_CONFIG_ID,
            ENTITY_TYPE_ALARM_ID,
            ENTITY_TYPE_SUBSCRIPTION_ID,
    })
    private @interface EntityType{}

    /**
     * Generates a hash for a given name and entity type, or returns an existing one.
     *
     * @param entityType The type of entity for namespacing.
     * @param name The name to hash.
     * @return A long hash value.
     */
    public long generateOrGetHash(@EntityType int entityType, String name) {
        String namespaced = namespace(entityType, name);
        if (mNameToHash.containsKey(namespaced)) {
            return mNameToHash.get(namespaced);
        }
        long hash = generateHash(namespaced);
        mNameToHash.put(namespaced, hash);
        return hash;
    }

    /**
     * Creates a namespaced string for hashing.
     *
     * @param entityType The type of entity.
     * @param name The name.
     * @return A namespaced string.
     */
    private String namespace(@EntityType int entityType, String name) {
        return entityType + ":" + name;
    }

    /**
     * Generates a unique hash for a namespaced string.
     *
     * @param namespaced The namespaced string to hash.
     * @return A unique long hash value.
     */
    private long generateHash(String namespaced) {
        long hash = HASH_FUNCTION.hashUnencodedChars(namespaced).asLong();
        BiMap<Long, String> hashToName = mNameToHash.inverse();
        while (hashToName.containsKey(hash)) {
            hash = hash++ % Long.MAX_VALUE;
        }
        return hash;
    }
}
```
