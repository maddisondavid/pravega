/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.containers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.pravega.common.ExceptionHelpers;
import io.pravega.common.Exceptions;
import io.pravega.common.LoggerHelpers;
import io.pravega.common.TimeoutTimer;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.util.AsyncMap;
import io.pravega.segmentstore.contracts.AttributeUpdate;
import io.pravega.segmentstore.contracts.SegmentProperties;
import io.pravega.segmentstore.contracts.StreamSegmentExistsException;
import io.pravega.segmentstore.contracts.StreamSegmentInformation;
import io.pravega.segmentstore.contracts.StreamSegmentNotExistsException;
import io.pravega.segmentstore.contracts.TooManyActiveSegmentsException;
import io.pravega.segmentstore.server.ContainerMetadata;
import io.pravega.segmentstore.server.DataCorruptionException;
import io.pravega.segmentstore.server.OperationLog;
import io.pravega.segmentstore.server.SegmentMetadata;
import io.pravega.segmentstore.server.logs.operations.StreamSegmentMapOperation;
import io.pravega.segmentstore.server.logs.operations.StreamSegmentMapping;
import io.pravega.segmentstore.server.logs.operations.TransactionMapOperation;
import io.pravega.segmentstore.storage.Storage;
import io.pravega.shared.segment.StreamSegmentNameUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Helps assign unique Ids to StreamSegments and persists them in Metadata.
 */
@Slf4j
@ThreadSafe
public class StreamSegmentMapper {
    //region Members

    private final String traceObjectId;
    private final ContainerMetadata containerMetadata;
    private final OperationLog durableLog;
    private final AsyncMap<String, SegmentState> stateStore;
    private final Supplier<CompletableFuture<Void>> metadataCleanup;
    private final Storage storage;
    private final Executor executor;
    @GuardedBy("assignmentLock")
    private final HashMap<String, PendingRequest> pendingRequests;
    private final Object assignmentLock = new Object();

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the StreamSegmentMapper class.
     *
     * @param containerMetadata The StreamSegmentContainerMetadata to bind to. All assignments are vetted from here,
     *                          but the Metadata is not touched directly from this component.
     * @param durableLog        The Durable Log to bind to. All assignments are durably stored here.
     * @param stateStore        A AsyncMap that can be used to store Segment State.
     * @param metadataCleanup   A callback returning a CompletableFuture that will be invoked when a foced metadata cleanup
     *                          is requested.
     * @param storage           The Storage to use for all external operations (create segment, get info, etc.)
     * @param executor          The executor to use for async operations.
     * @throws NullPointerException If any of the arguments are null.
     */
    public StreamSegmentMapper(ContainerMetadata containerMetadata, OperationLog durableLog, AsyncMap<String, SegmentState> stateStore,
                               Supplier<CompletableFuture<Void>> metadataCleanup, Storage storage, Executor executor) {
        Preconditions.checkNotNull(containerMetadata, "containerMetadata");
        Preconditions.checkNotNull(durableLog, "durableLog");
        Preconditions.checkNotNull(stateStore, "stateStore");
        Preconditions.checkNotNull(metadataCleanup, "metadataCleanup");
        Preconditions.checkNotNull(storage, "storage");
        Preconditions.checkNotNull(executor, "executor");

        this.traceObjectId = String.format("StreamSegmentMapper[%d]", containerMetadata.getContainerId());
        this.containerMetadata = containerMetadata;
        this.durableLog = durableLog;
        this.stateStore = stateStore;
        this.metadataCleanup = metadataCleanup;
        this.storage = storage;
        this.executor = executor;
        this.pendingRequests = new HashMap<>();
    }

    //endregion

    //region Create Segments

    /**
     * Creates a new StreamSegment with given name (in Storage) and persists the given attributes (in Storage).
     *
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param attributes        The initial attributes for the StreamSegment, if any.
     * @param timeout           Timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will indicate the operation completed normally.
     * If the operation failed, this will contain the exception that caused the failure.
     */
    public CompletableFuture<Void> createNewStreamSegment(String streamSegmentName, Collection<AttributeUpdate> attributes, Duration timeout) {
        long traceId = LoggerHelpers.traceEnterWithContext(log, traceObjectId, "createNewStreamSegment", streamSegmentName);
        long segmentId = this.containerMetadata.getStreamSegmentId(streamSegmentName, true);
        if (isValidStreamSegmentId(segmentId)) {
            // Quick fail: see if this is an active Segment, and if so, don't bother with anything else.
            return FutureHelpers.failedFuture(new StreamSegmentExistsException(streamSegmentName));
        }

        TimeoutTimer timer = new TimeoutTimer(timeout);
        CompletableFuture<Void> result = this.storage
                .create(streamSegmentName, timer.getRemaining())
                .thenComposeAsync(si -> this.stateStore.put(streamSegmentName, getState(si, attributes), timer.getRemaining()), this.executor);
        if (log.isTraceEnabled()) {
            result.thenAccept(v -> LoggerHelpers.traceLeave(log, traceObjectId, "createNewStreamSegment", traceId, streamSegmentName));
        }

        return result;
    }

    /**
     * Creates a new Transaction StreamSegment for an existing Parent StreamSegment and persists the given attributes (in Storage).
     *
     * @param parentStreamSegmentName The case-sensitive StreamSegment Name of the Parent StreamSegment.
     * @param transactionId           A unique identifier for the transaction to be created.
     * @param attributes              The initial attributes for the Transaction, if any.
     * @param timeout                 Timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the name of the newly created Transaction StreamSegment.
     * If the operation failed, this will contain the exception that caused the failure.
     * @throws IllegalArgumentException If the given parent StreamSegment cannot have a Transaction (because it is deleted, sealed, inexistent).
     */
    public CompletableFuture<String> createNewTransactionStreamSegment(String parentStreamSegmentName, UUID transactionId,
                                                                       Collection<AttributeUpdate> attributes, Duration timeout) {
        long traceId = LoggerHelpers.traceEnterWithContext(log, traceObjectId, "createNewTransactionStreamSegment", parentStreamSegmentName);

        // We cannot create a Transaction StreamSegment for a what looks like another Transaction.
        Exceptions.checkArgument(
                StreamSegmentNameUtils.getParentStreamSegmentName(parentStreamSegmentName) == null,
                "parentStreamSegmentName",
                "Cannot create a Transaction for a Transaction.");

        // Validate that Parent StreamSegment exists.
        TimeoutTimer timer = new TimeoutTimer(timeout);
        CompletableFuture<Void> parentCheck = null;
        long mappedParentId = this.containerMetadata.getStreamSegmentId(parentStreamSegmentName, true);
        if (isValidStreamSegmentId(mappedParentId)) {
            SegmentProperties parentInfo = this.containerMetadata.getStreamSegmentMetadata(mappedParentId);
            if (parentInfo != null) {
                parentCheck = validateParentSegmentEligibility(parentInfo);
            }
        }

        if (parentCheck == null) {
            // The parent is not registered in the metadata. Get required info from Storage and don't map it unnecessarily.
            parentCheck = this.storage
                    .getStreamSegmentInfo(parentStreamSegmentName, timer.getRemaining())
                    .thenCompose(this::validateParentSegmentEligibility);
        }

        String transactionName = StreamSegmentNameUtils.getTransactionNameFromId(parentStreamSegmentName, transactionId);
        return parentCheck
                .thenComposeAsync(parentId -> this.storage.create(transactionName, timer.getRemaining()), this.executor)
                .thenComposeAsync(transInfo -> this.stateStore.put(transInfo.getName(), getState(transInfo, attributes), timer.getRemaining()), this.executor)
                .thenApply(v -> {
                    LoggerHelpers.traceLeave(log, traceObjectId, "createNewTransactionStreamSegment", traceId, parentStreamSegmentName, transactionName);
                    return transactionName;
                });
    }

    //endregion

    //region Segment Id Assignment

    /**
     * Attempts to get an existing StreamSegmentId for the given case-sensitive StreamSegment Name, and then invokes the
     * given Function with the Id.
     * * If the Segment is already mapped in the Metadata, the existing Id is used.
     * * Otherwise if the Segment had previously been assigned an id (and saved in the State Store), that Id will be
     * reused.
     * * Otherwise, it atomically assigns a new Id and stores it in the Metadata and DurableLog.
     * <p>
     * If multiple requests for assignment arrive for the same StreamSegment in parallel (or while an assignment is in progress),
     * they will be queued up in the order received and will be invoked in the same order after assignment
     * <p>
     * If the given streamSegmentName refers to a Transaction StreamSegment, this will attempt to validate that the Transaction is still
     * valid, by which means it will check the Parent's existence alongside the Transaction's existence.
     *
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param timeout           The timeout for the operation.
     * @param thenCompose       A Function that consumes a StreamSegmentId and returns a CompletableFuture that will indicate
     *                          when the consumption of that StreamSegmentId is complete. This Function will be invoked
     *                          synchronously if the StreamSegmentId is already mapped, or async, otherwise, after assignment.
     * @param <T>               Type of the return value.
     * @return A CompletableFuture that, when completed normally, will contain the result of the given Function (thenCompose)
     * applied to the assigned/retrieved StreamSegmentId. If failed, this will contain the exception that caused the failure.
     */
    <T> CompletableFuture<T> getOrAssignStreamSegmentId(String streamSegmentName, Duration timeout, Function<Long, CompletableFuture<T>> thenCompose) {
        // Check to see if the metadata already knows about this Segment.
        Preconditions.checkNotNull(thenCompose, "thenCompose");
        long streamSegmentId = this.containerMetadata.getStreamSegmentId(streamSegmentName, true);
        if (isValidStreamSegmentId(streamSegmentId)) {
            // We already have a value, just return it (but make sure the Segment has not been deleted).
            if (this.containerMetadata.getStreamSegmentMetadata(streamSegmentId).isDeleted()) {
                return FutureHelpers.failedFuture(new StreamSegmentNotExistsException(streamSegmentName));
            } else {
                // Even though we have the value in the metadata, we need to be very careful not to invoke this callback
                // before any other existing callbacks are invoked. As such, verify if we have an existing PendingRequest
                // for this segment - if so, tag onto it so we invoke these callbacks in the correct order.
                QueuedCallback<T> queuedCallback = null;
                synchronized (this.assignmentLock) {
                    PendingRequest pendingRequest = this.pendingRequests.getOrDefault(streamSegmentName, null);
                    if (pendingRequest != null) {
                        queuedCallback = new QueuedCallback<>(thenCompose);
                        pendingRequest.callbacks.add(queuedCallback);
                    }
                }

                return queuedCallback == null ? thenCompose.apply(streamSegmentId) : queuedCallback.result;
            }
        }

        // See if anyone else is currently waiting to get this StreamSegment's id.
        QueuedCallback<T> queuedCallback;
        boolean needsAssignment = false;
        synchronized (this.assignmentLock) {
            PendingRequest pendingRequest = this.pendingRequests.getOrDefault(streamSegmentName, null);
            if (pendingRequest == null) {
                needsAssignment = true;
                pendingRequest = new PendingRequest();
                this.pendingRequests.put(streamSegmentName, pendingRequest);
            }

            queuedCallback = new QueuedCallback<>(thenCompose);
            pendingRequest.callbacks.add(queuedCallback);
        }

        // We are the first/only ones requesting this id; go ahead and assign an id.
        if (needsAssignment) {
            // Determine if given StreamSegmentName is actually a Transaction StreamSegmentName.
            String parentStreamSegmentName = StreamSegmentNameUtils.getParentStreamSegmentName(streamSegmentName);
            if (parentStreamSegmentName == null) {
                // Stand-alone StreamSegment.
                this.executor.execute(() -> assignStreamSegmentId(streamSegmentName, timeout));
            } else {
                this.executor.execute(() -> assignTransactionStreamSegmentId(streamSegmentName, parentStreamSegmentName, timeout));
            }
        }

        return queuedCallback.result;
    }

    /**
     * Same as getOrAssignStreamSegmentId(String, Duration, Function) except that this simply returns a CompletableFuture
     * with the SegmentId.
     *
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param timeout           The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the result of the given Function (thenCompose)
     * applied to the assigned/retrieved StreamSegmentId. If failed, this will contain the exception that caused the failure.
     */
    @VisibleForTesting
    public CompletableFuture<Long> getOrAssignStreamSegmentId(String streamSegmentName, Duration timeout) {
        return getOrAssignStreamSegmentId(streamSegmentName, timeout, CompletableFuture::completedFuture);
    }

    /**
     * Attempts to map a Transaction StreamSegment to its parent StreamSegment (and assign an id in the process, if needed).
     *
     * @param transactionSegmentName The Name for the Transaction to assign Id for.
     * @param parentSegmentName      The Name of the Parent StreamSegment.
     * @param timeout                The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    private CompletableFuture<Long> assignTransactionStreamSegmentId(String transactionSegmentName, String parentSegmentName, Duration timeout) {
        TimeoutTimer timer = new TimeoutTimer(timeout);
        AtomicReference<Long> parentSegmentId = new AtomicReference<>();

        // Get info about parent. This also verifies the parent exists.
        return withFailureHandler(
                getOrAssignStreamSegmentId(parentSegmentName, timer.getRemaining(),
                        id -> {
                            // Get info about Transaction itself.
                            parentSegmentId.set(id);
                            return this.storage.getStreamSegmentInfo(transactionSegmentName, timer.getRemaining());
                        })
                        .thenCompose(transInfo -> retrieveAttributes(transInfo, timer.getRemaining()))
                        .thenCompose(transInfo -> assignTransactionStreamSegmentId(transInfo, parentSegmentId.get(), timer.getRemaining())),
                transactionSegmentName);
    }

    /**
     * Attempts to map a Transaction StreamSegment to its parent StreamSegment (and assign an id in the process, if needed).
     *
     * @param transInfo             The SegmentInfo for the Transaction to assign id for.
     * @param parentStreamSegmentId The ID of the Parent StreamSegment.
     * @param timeout               The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    private CompletableFuture<Long> assignTransactionStreamSegmentId(SegmentInfo transInfo, long parentStreamSegmentId, Duration timeout) {
        assert transInfo != null : "transInfo is null";
        assert parentStreamSegmentId != ContainerMetadata.NO_STREAM_SEGMENT_ID : "parentStreamSegmentId is invalid.";
        return submitToOperationLogWithRetry(transInfo, parentStreamSegmentId, timeout);
    }

    /**
     * Attempts to map a StreamSegment to an Id, by first trying to retrieve an existing id, and, should that not exist,
     * assign a new one.
     *
     * @param streamSegmentName The name of the StreamSegment to map.
     * @param timeout           Timeout for the operation.
     */
    private void assignStreamSegmentId(String streamSegmentName, Duration timeout) {
        TimeoutTimer timer = new TimeoutTimer(timeout);
        withFailureHandler(this.storage
                        .getStreamSegmentInfo(streamSegmentName, timer.getRemaining())
                        .thenComposeAsync(si -> retrieveAttributes(si, timer.getRemaining()), this.executor)
                        .thenComposeAsync(si -> submitToOperationLogWithRetry(si, ContainerMetadata.NO_STREAM_SEGMENT_ID, timer.getRemaining()), this.executor),
                streamSegmentName);
    }

    /**
     * Returns a SegmentState for the given SegmentProperties, but with the given attribute updates applied.
     *
     * @param source           The base SegmentProperties to use.
     * @param attributeUpdates A collection of attribute updates to apply.
     * @return A SegmentState which contains the same information as source, but with applied attribute updates.
     */
    private SegmentState getState(SegmentProperties source, Collection<AttributeUpdate> attributeUpdates) {
        if (attributeUpdates == null) {
            // Nothing to do.
            return new SegmentState(ContainerMetadata.NO_STREAM_SEGMENT_ID, source);
        }

        // Merge updates into the existing attributes.
        Map<UUID, Long> attributes = new HashMap<>(source.getAttributes());
        attributeUpdates.forEach(au -> attributes.put(au.getAttributeId(), au.getValue()));
        return new SegmentState(ContainerMetadata.NO_STREAM_SEGMENT_ID, new StreamSegmentInformation(source, attributes));
    }

    /**
     * Fetches the attributes for the given source segment and returns a new SegmentProperties with the same information
     * as the given source, but the attributes fetched from the SegmentStateStore.
     *
     * @param source  A SegmentProperties describing the Segment to fetch attributes for.
     * @param timeout Timeout for the operation.
     * @return A CompletableFuture that, when completed, will contain a new instance of the SegmentProperties with the
     * same information as source, but with attributes attached.
     */
    private CompletableFuture<SegmentInfo> retrieveAttributes(SegmentProperties source, Duration timeout) {
        return this.stateStore
                .get(source.getName(), timeout)
                .thenApply(state -> {
                    if (state == null) {
                        // Nothing to change.
                        return new SegmentInfo(ContainerMetadata.NO_STREAM_SEGMENT_ID, source);
                    }

                    if (!source.getName().equals(state.getSegmentName())) {
                        throw new CompletionException(new DataCorruptionException(
                                String.format("Stored State for segment '%s' is corrupted. It refers to a different segment '%s'.",
                                        source.getName(),
                                        state.getSegmentName())));
                    }

                    return new SegmentInfo(state.getSegmentId(), new StreamSegmentInformation(source, state.getAttributes()));
                });
    }

    /**
     * Same as submitToOperationLog, but retries exactly once in case TooManyActiveSegmentsException was encountered, in
     * which case it forces a metadata cleanup before retrying. If the second attempt also fails, there will be no more retry
     * and the Exception from the second failure will be the one that this call fails with too.
     *
     * @param segmentInfo           The SegmentInfo for the StreamSegment to generate and persist.
     * @param parentStreamSegmentId If different from ContainerMetadata.NO_STREAM_SEGMENT_ID, the given streamSegmentInfo
     *                              will be mapped as a transaction. Otherwise, this will be registered as a standalone StreamSegment.
     * @param timeout               Timeout for the operation.
     * @return A CompletableFuture that, when completed, will contain the internal SegmentId that was assigned (or the
     * one supplied via SegmentInfo, if any). If the operation failed, then this Future will complete with that exception.
     */
    private CompletableFuture<Long> submitToOperationLogWithRetry(SegmentInfo segmentInfo, long parentStreamSegmentId, Duration timeout) {
        return retryWithCleanup(() -> submitToOperationLog(segmentInfo, parentStreamSegmentId, timeout));
    }

    /**
     * Submits a StreamSegmentMapOperation or TransactionMapOperation to the OperationLog. Upon completion, this operation
     * will have mapped the given Segment to a new internal Segment Id if none was provided in the given SegmentInfo.
     * If the given SegmentInfo already has a SegmentId set, then all efforts will be made to map that Segment with the
     * requested Segment Id.
     *
     * @param segmentInfo           The SegmentInfo for the StreamSegment to generate and persist.
     * @param parentStreamSegmentId If different from ContainerMetadata.NO_STREAM_SEGMENT_ID, the given streamSegmentInfo
     *                              will be mapped as a transaction. Otherwise, this will be registered as a standalone StreamSegment.
     * @param timeout               Timeout for the operation.
     * @return A CompletableFuture that, when completed, will contain the internal SegmentId that was assigned (or the
     * one supplied via SegmentInfo, if any). If the operation failed, then this Future will complete with that exception.
     */
    private CompletableFuture<Long> submitToOperationLog(SegmentInfo segmentInfo, long parentStreamSegmentId, Duration timeout) {
        SegmentProperties properties = segmentInfo.getProperties();
        if (properties.isDeleted()) {
            // Stream does not exist. Fail the request with the appropriate exception.
            failAssignment(properties.getName(), new StreamSegmentNotExistsException("StreamSegment does not exist."));
            return FutureHelpers.failedFuture(new StreamSegmentNotExistsException(properties.getName()));
        }

        long existingSegmentId = this.containerMetadata.getStreamSegmentId(properties.getName(), true);
        if (isValidStreamSegmentId(existingSegmentId)) {
            // Looks like someone else beat us to it.
            completeAssignment(properties.getName(), existingSegmentId);
            return CompletableFuture.completedFuture(existingSegmentId);
        } else {
            CompletableFuture<Void> logAddResult;
            StreamSegmentMapping mapping;
            if (isValidStreamSegmentId(parentStreamSegmentId)) {
                // Transaction.
                SegmentMetadata parentMetadata = this.containerMetadata.getStreamSegmentMetadata(parentStreamSegmentId);
                assert parentMetadata != null : "parentMetadata is null";
                TransactionMapOperation op = new TransactionMapOperation(parentStreamSegmentId, properties);
                mapping = applySegmentId(segmentInfo, op);
                logAddResult = this.durableLog.add(op, timeout);
            } else {
                // Standalone StreamSegment.
                StreamSegmentMapOperation op = new StreamSegmentMapOperation(properties);
                mapping = applySegmentId(segmentInfo, op);
                logAddResult = this.durableLog.add(op, timeout);
            }

            return logAddResult
                    .thenApply(seqNo -> completeAssignment(properties.getName(), mapping.getStreamSegmentId()));
        }
    }

    /**
     * Copies the Segment Id from the given SegmentInfo to the given Mapping, if any is defined.
     *
     * @param segmentInfo The source SegmentInfo to get the StreamSegmentId.
     * @param mapping     The StreamSegmentMapping to set the StreamSegmentId to.
     */
    private StreamSegmentMapping applySegmentId(SegmentInfo segmentInfo, StreamSegmentMapping mapping) {
        if (segmentInfo.getSegmentId() != ContainerMetadata.NO_STREAM_SEGMENT_ID) {
            mapping.setStreamSegmentId(segmentInfo.getSegmentId());
        }

        return mapping;
    }

    /**
     * Completes the assignment for the given StreamSegmentName by completing the waiting CompletableFuture.
     */
    private long completeAssignment(String streamSegmentName, long streamSegmentId) {
        assert streamSegmentId != ContainerMetadata.NO_STREAM_SEGMENT_ID : "no valid streamSegmentId given";
        finishPendingRequests(streamSegmentName, PendingRequest::complete, streamSegmentId);
        return streamSegmentId;
    }

    /**
     * Fails the assignment for the given StreamSegment Id with the given reason.
     */
    private void failAssignment(String streamSegmentName, Throwable reason) {
        finishPendingRequests(streamSegmentName, PendingRequest::completeExceptionally, reason);
    }

    private <T> void finishPendingRequests(String streamSegmentName, BiConsumer<PendingRequest, T> completionMethod, T completionArgument) {
        assert streamSegmentName != null : "no streamSegmentName given";
        // Get any pending requests and complete all of them, in order. We are running this in a loop (and replacing
        // the existing PendingRequest with an empty one) because more requests may come in while we are executing the
        // callbacks. In such cases, we collect the new requests in the new object and check it again, after we are done
        // with the current executions.
        while (true) {
            PendingRequest pendingRequest;
            synchronized (this.assignmentLock) {
                pendingRequest = this.pendingRequests.remove(streamSegmentName);
                if (pendingRequest == null || pendingRequest.callbacks.size() == 0) {
                    // No more requests. Safe to exit.
                    break;
                } else {
                    this.pendingRequests.put(streamSegmentName, new PendingRequest());
                }
            }

            completionMethod.accept(pendingRequest, completionArgument);
        }
    }

    private CompletableFuture<Long> withFailureHandler(CompletableFuture<Long> source, String segmentName) {
        return source.exceptionally(ex -> {
            failAssignment(segmentName, ex);
            throw new CompletionException(ex);
        });
    }

    private CompletableFuture<Void> validateParentSegmentEligibility(SegmentProperties parentInfo) {
        if (parentInfo.isDeleted() || parentInfo.isSealed()) {
            return FutureHelpers.failedFuture(new IllegalArgumentException("Cannot create a Transaction for a deleted or sealed Segment."));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private boolean isValidStreamSegmentId(long id) {
        return id != ContainerMetadata.NO_STREAM_SEGMENT_ID;
    }

    /**
     * Retries Future from the given supplier exactly once if encountering TooManyActiveSegmentsException.
     *
     * @param toTry A Supplier that returns a Future to execute. This will be invoked either once or twice.
     * @param <T>   Return type of Future.
     * @return A CompletableFuture with the result, or failure cause.
     */
    private <T> CompletableFuture<T> retryWithCleanup(Supplier<CompletableFuture<T>> toTry) {
        CompletableFuture<T> result = new CompletableFuture<>();
        toTry.get()
             .thenAccept(result::complete)
             .exceptionally(ex -> {
                 // Check if the exception indicates the Metadata has reached capacity. In that case, force a cleanup
                 // and try again, exactly once.
                 try {
                     if (ExceptionHelpers.getRealException(ex) instanceof TooManyActiveSegmentsException) {
                         log.debug("{}: Forcing metadata cleanup due to capacity exceeded ({}).", this.traceObjectId,
                                 ExceptionHelpers.getRealException(ex).getMessage());
                         CompletableFuture<T> f = this.metadataCleanup.get().thenComposeAsync(v -> toTry.get(), this.executor);
                         f.thenAccept(result::complete);
                         FutureHelpers.exceptionListener(f, result::completeExceptionally);
                     } else {
                         result.completeExceptionally(ex);
                     }
                 } catch (Throwable t) {
                     result.completeExceptionally(t);
                     throw t;
                 }

                 return null;
             });

        return result;
    }

    //endregion

    //region Helper Classes

    @Data
    private static class SegmentInfo {
        private final long segmentId;
        private final SegmentProperties properties;
    }

    /**
     * A pending request for a Segment Assignment, which keeps track of all queued callbacks.
     * Note that this class in itself is not thread safe, so the caller should take precautions to ensure thread safety.
     */
    @NotThreadSafe
    private static class PendingRequest {
        private final ArrayList<QueuedCallback<?>> callbacks = new ArrayList<>();

        /**
         * Invokes all queued callbacks, in order, with the given SegmentId as a parameter.
         */
        void complete(long segmentId) {
            for (QueuedCallback<?> callback : this.callbacks) {
                try {
                    callback.complete(segmentId);
                } catch (Throwable ex) {
                    callback.completeExceptionally(ex);
                }
            }
        }

        /**
         * Invokes all queued callbacks, in order, with the given Throwable as a failure cause.
         */
        void completeExceptionally(Throwable ex) {
            for (QueuedCallback<?> callback : this.callbacks) {
                callback.completeExceptionally(ex);
            }
        }
    }

    /**
     * A single callback that is queued up for a Pending Request. The 'result' is what is returned to the caller, which
     * is completed indirectly with the result of the invocation to 'callback'.
     */
    @RequiredArgsConstructor
    private static class QueuedCallback<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final Function<Long, CompletableFuture<T>> callback;

        void complete(long segmentId) {
            FutureHelpers.completeAfter(() -> this.callback.apply(segmentId), this.result);
        }

        void completeExceptionally(Throwable ex) {
            this.result.completeExceptionally(ex);
        }
    }

    //endregion
}
