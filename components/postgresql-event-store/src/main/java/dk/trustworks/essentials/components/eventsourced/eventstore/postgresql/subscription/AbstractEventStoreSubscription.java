/*
 * Copyright 2021-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.foundation.types.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Abstract base class for EventStoreSubscription implementations.
 * Provides common functionality and fields used by all subscription types.
 */
public abstract class AbstractEventStoreSubscription implements EventStoreSubscription {
    protected final Logger log;

    protected final EventStore eventStore;
    protected final AggregateType aggregateType;
    protected final SubscriberId subscriberId;
    protected final Optional<Tenant> onlyIncludeEventsForTenant;
    protected final EventStoreSubscriptionObserver eventStoreSubscriptionObserver;
    protected final Consumer<EventStoreSubscription> unsubscribeCallback;

    protected volatile boolean started;

    /**
     * Constructor with common parameters for all subscription types
     *
     * @param eventStore The event store
     * @param aggregateType The aggregate type to subscribe to
     * @param subscriberId The subscriber ID
     * @param onlyIncludeEventsForTenant Optional tenant filter
     * @param eventStoreSubscriptionObserver The subscription observer
     * @param unsubscribeCallback Callback to execute when unsubscribing
     */
    protected AbstractEventStoreSubscription(EventStore eventStore,
                                             AggregateType aggregateType,
                                             SubscriberId subscriberId,
                                             Optional<Tenant> onlyIncludeEventsForTenant,
                                             EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                             Consumer<EventStoreSubscription> unsubscribeCallback) {
        this.log = LoggerFactory.getLogger(this.getClass());
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        this.onlyIncludeEventsForTenant = requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant provided");
        this.eventStoreSubscriptionObserver = requireNonNull(eventStoreSubscriptionObserver, "No eventStoreSubscriptionObserver provided");
        this.unsubscribeCallback = requireNonNull(unsubscribeCallback, "No unsubscribeCallback provided");
    }

    @Override
    public SubscriberId subscriberId() {
        return subscriberId;
    }

    @Override
    public AggregateType aggregateType() {
        return aggregateType;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void unsubscribe() {
        log.info("[{}-{}] Initiating unsubscription",
                subscriberId,
                aggregateType);
        eventStoreSubscriptionObserver.unsubscribing(this);
        unsubscribeCallback.accept(this);
    }

    @Override
    public Optional<Tenant> onlyIncludeEventsForTenant() {
        return onlyIncludeEventsForTenant;
    }

    /**
     * Common error handling for persisted events
     * 
     * @param e The persisted event that caused the error
     * @param cause The cause of the error
     */
    protected void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
        log.error("[{}-{}] (#{}) Skipping {} event because of error",
                subscriberId,
                aggregateType,
                e.globalEventOrder(),
                e.event().getEventTypeOrName().getValue(), cause);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "aggregateType=" + aggregateType +
                ", subscriberId=" + subscriberId +
                ", onlyIncludeEventsForTenant=" + onlyIncludeEventsForTenant +
                ", started=" + started +
                '}';
    }
}