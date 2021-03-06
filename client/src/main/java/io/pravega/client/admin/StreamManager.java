/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.client.admin;

import io.pravega.client.admin.impl.StreamManagerImpl;
import io.pravega.client.stream.StreamConfiguration;
import java.net.URI;

/**
 * Used to create, delete, and manage Streams and ReaderGroups.
 */
public interface StreamManager extends AutoCloseable {

    /**
     * Creates a new instance of StreamManager.
     *
     * @param controller The Controller URI.
     * @return Instance of Stream Manager implementation.
     */
    public static StreamManager create(URI controller) {
        return new StreamManagerImpl(controller);
    }
    
    /**
     * Creates a new stream
     * <p>
     * Note: This method is idempotent assuming called with the same name and config. This method
     * may block.
     *
     * @param scopeName  The name of the scope to create this stream in.
     * @param streamName The name of the stream to be created.
     * @param config The configuration the stream should use.
     * @return True if stream is created
     */
    boolean createStream(String scopeName, String streamName, StreamConfiguration config);

    /**
     * Change the configuration for an existing stream.
     * <p>
     * Note:
     * This method is idempotent assuming called with the same name and config.
     * This method may block.
     *
     * @param scopeName  The name of the scope to create this stream in.
     * @param streamName The name of the stream who's config is to be changed.
     * @param config     The new configuration.
     * @return True if stream configuration is updated
     */
    boolean updateStream(String scopeName, String streamName, StreamConfiguration config);

    /**
     * Seal an existing stream.
     *
     * @param scopeName  The name of the scope to create this stream in.
     * @param streamName The name of the stream which has to be sealed.
     * @return True if stream is sealed
     */
    boolean sealStream(String scopeName, String streamName);
    
    /**
     * Deletes the provided stream. No more events may be written or read.
     * Resources used by the stream will be freed.
     *
     * @param scopeName  The name of the scope to create this stream in.
     * @param toDelete The name of the stream to be deleted.
     * @return True if stream is deleted
     */
    boolean deleteStream(String scopeName, String toDelete);

    /**
     * Creates a new scope.
     *
     * @param scopeName  The name of the scope to create this stream in.
     * @return True if scope is created
     */
    boolean createScope(String scopeName);

    /**
     * Deletes an existing scope. The scope must contain no
     * stream.
     *
     * @param scopeName  The name of the scope to create this stream in.
     * @return True if scope is deleted
     */
    boolean deleteScope(String scopeName);
    
    /**
     * Closes the stream manager.
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    void close();
}
