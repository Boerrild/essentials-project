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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;


import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsProperties.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.*;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import dk.trustworks.essentials.components.foundation.interceptor.micrometer.*;
import dk.trustworks.essentials.components.foundation.jdbi.EssentialsQueryTagger;
import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.lifecycle.*;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.stats.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.postgresql.ttl.PostgresqlTTLManager;
import dk.trustworks.essentials.components.foundation.postgresql.micrometer.RecordSqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.scheduler.*;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.ttl.TTLJobBeanPostProcessor;
import dk.trustworks.essentials.components.queue.postgresql.*;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.*;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.reactive.command.interceptor.CommandBusInterceptor;
import dk.trustworks.essentials.reactive.spring.ReactiveHandlersBeanPostProcessor;
import dk.trustworks.essentials.shared.security.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.slf4j.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Postgresql focused Essentials Components auto configuration<br>
 * <br>
 * <u><b>Security:</b></u><br>
 * If you in your own Spring Boot application choose to override the Beans defined by this starter,
 * then you need to check the component document to learn about the Security implications of each configuration.
 * <br>
 * <u>{@link PostgresqlFencedLockManager}</u><br>
 * To support customization of {@link PostgresqlFencedLockManager} storage table name, the {@link EssentialsComponentsProperties#getFencedLockManager()}'s {@link FencedLockManagerProperties#setFencedLocksTableName(String)}
 * will be directly used in constructing SQL statements through string concatenation, which exposes the component to SQL injection attacks.<br>
 * <br>
 * It is the responsibility of the user of this component to sanitize the {@code fencedLocksTableName}
 * to ensure the security of all the SQL statements generated by this component. The {@link PostgresqlFencedLockStorage} component will
 * call the {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} method to validate the table name as a first line of defense.<br>
 * However, Essentials components as well as {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} does not offer exhaustive protection, nor does it assure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters,  column, table, and index names.<br>
 * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
 * <br>
 * It is highly recommended that the {@code fencedLocksTableName} value is only derived from a controlled and trusted source.<br>
 * To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code fencedLocksTableName} value.<br>
 * <b>Failure to adequately sanitize and validate this value could expose the application to SQL injection
 * vulnerabilities, compromising the security and integrity of the database.</b><br>
 * <br>
 * <u>{@link PostgresqlDurableQueues}</u><br>
 * To support customization of {@link PostgresqlDurableQueues} storage table name, the {@link EssentialsComponentsProperties#getDurableQueues()}'s {@link DurableQueuesProperties#setSharedQueueTableName(String)}
 * will be directly used in constructing SQL statements through string concatenation, which exposes the component to SQL injection attacks.<br>
 * It is the responsibility of the user of this component to sanitize the {@code sharedQueueTableName}
 * to ensure the security of all the SQL statements generated by this component. The {@link PostgresqlDurableQueues} component will
 * call the {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} method to validate the table name as a first line of defense.<br>
 * The {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} provides an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
 * However, Essentials components as well as {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} does not offer exhaustive protection, nor does it assure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters,  column, table, and index names.<br>
 * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
 * <br>
 * It is highly recommended that the {@code sharedQueueTableName} value is only derived from a controlled and trusted source.<br>
 * To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@code sharedQueueTableName} value.<br>
 * <b>Failure to adequately sanitize and validate this value could expose the application to SQL injection
 * vulnerabilities, compromising the security and integrity of the database.</b>
 *
 * @see dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues
 * @see dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager
 * @see dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockStorage
 * @see MultiTableChangeListener
 */
@AutoConfiguration
@EnableConfigurationProperties(EssentialsComponentsProperties.class)
public class EssentialsComponentsConfiguration {
    public static final Logger log = LoggerFactory.getLogger(EssentialsComponentsConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "management.tracing", name = "enabled", havingValue = "true")
    public DurableQueuesMicrometerTracingInterceptor durableQueuesMicrometerTracingInterceptor(Optional<Tracer> tracer,
                                                                                               Optional<Propagator> propagator,
                                                                                               Optional<ObservationRegistry> observationRegistry,
                                                                                               EssentialsComponentsProperties properties) {
        return new DurableQueuesMicrometerTracingInterceptor(tracer.get(),
                                                             propagator.get(),
                                                             observationRegistry.get(),
                                                             properties.getDurableQueues().isVerboseTracing());
    }

    @Bean
    @ConditionalOnProperty(prefix = "management.tracing", name = "enabled", havingValue = "true")
    public DurableQueuesMicrometerInterceptor durableQueuesMicrometerInterceptor(Optional<MeterRegistry> meterRegistry,
                                                                                 EssentialsComponentsProperties properties) {
        return new DurableQueuesMicrometerInterceptor(meterRegistry.get(), properties.getTracingProperties().getModuleTag());
    }

    /**
     * Auto-registers any {@link CommandHandler} with the single {@link CommandBus} bean found<br>
     * AND auto-registers any {@link EventHandler} with all {@link EventBus} beans found
     *
     * @return the {@link ReactiveHandlersBeanPostProcessor} bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials", name = "reactive-bean-post-processor-enabled", havingValue = "true", matchIfMissing = true)
    public static ReactiveHandlersBeanPostProcessor reactiveHandlersBeanPostProcessor() {
        return new ReactiveHandlersBeanPostProcessor();
    }


    /**
     * Essential Jackson module which adds support for serializing and deserializing any Essentials types (note: Map keys still needs to be explicitly defined - see doc)
     *
     * @return the Essential Jackson module which adds support for serializing and deserializing any Essentials types
     */
    @Bean
    @ConditionalOnMissingBean
    public EssentialTypesJacksonModule essentialJacksonModule() {
        return new EssentialTypesJacksonModule();
    }

    /**
     * Essential Immutable Jackson module which adds support for serializing and deserializing objects with no {@link JsonCreator} or a default constructor
     *
     * @return the Essential Immutable Jackson module which adds support for serializing and deserializing objects with no {@link JsonCreator} or a default constructor
     */
    @Bean
    @ConditionalOnClass(name = "org.objenesis.ObjenesisStd")
    @ConditionalOnProperty(prefix = "essentials", name = "immutable-jackson-module-enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public EssentialsImmutableJacksonModule essentialsImmutableJacksonModule() {
        return new EssentialsImmutableJacksonModule();
    }

    /**
     * {@link Jdbi} is the JDBC API used by the all the Postgresql specific components such as
     * PostgresqlEventStore, {@link PostgresqlFencedLockManager} and {@link PostgresqlDurableQueues}
     *
     * @param dataSource the Spring managed datasource
     * @return the {@link Jdbi} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public Jdbi jdbi(DataSource dataSource,
                     EssentialsComponentsProperties properties,
                     Optional<MeterRegistry> meterRegistry) {
        var jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
        jdbi.installPlugin(new PostgresPlugin());
        if (properties.getMetrics().getSql().isEnabled()) {
            EssentialsQueryTagger.tagQueries(jdbi);
            jdbi.setSqlLogger(new RecordSqlExecutionTimeLogger(meterRegistry,
                                                               properties.getMetrics().getSql().isEnabled(),
                                                               properties.getMetrics().getSql().toLogThresholds(),
                                                               properties.getTracingProperties().getModuleTag())
                             );
        } else {
            jdbi.setSqlLogger(new SqlExecutionTimeLogger());
        }

        return jdbi;
    }

    /**
     * Define the {@link SpringTransactionAwareJdbiUnitOfWorkFactory}, but only if an EventStore specific variant isn't on the classpath.<br>
     * The {@link SpringTransactionAwareJdbiUnitOfWorkFactory} supports joining {@link UnitOfWork}'s
     * with the underlying Spring managed Transaction (i.e. supports methods annotated with @Transactional)
     *
     * @param jdbi               the jdbi instance
     * @param transactionManager the Spring Transactional manager as we allow Spring to demarcate the transaction
     * @return The {@link SpringTransactionAwareJdbiUnitOfWorkFactory}
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnMissingClass("dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.SpringTransactionAwareEventStoreUnitOfWorkFactory")
    public HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory(Jdbi jdbi,
                                                                                           PlatformTransactionManager transactionManager) {
        return new SpringTransactionAwareJdbiUnitOfWorkFactory(jdbi, transactionManager);
    }

    /**
     * The {@link PostgresqlFencedLockManager} that coordinates distributed locks
     *
     * @param jdbi              the jbdi instance
     * @param unitOfWorkFactory the {@link UnitOfWorkFactory} for coordinating {@link UnitOfWork}/Transactions
     * @param eventBus          the {@link EventBus} where {@link FencedLockEvents} are published
     * @param properties        the auto configure properties
     * @return The {@link PostgresqlFencedLockManager}
     */
    @Bean
    @ConditionalOnMissingBean
    public FencedLockManager fencedLockManager(Jdbi jdbi,
                                               HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                               EventBus eventBus,
                                               EssentialsComponentsProperties properties) {
        return PostgresqlFencedLockManager.builder()
                                          .setJdbi(jdbi)
                                          .setUnitOfWorkFactory(unitOfWorkFactory)
                                          .setLockTimeOut(properties.getFencedLockManager().getLockTimeOut())
                                          .setLockConfirmationInterval(properties.getFencedLockManager().getLockConfirmationInterval())
                                          .setFencedLocksTableName(properties.getFencedLockManager().getFencedLocksTableName())
                                          .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(properties.getFencedLockManager().isReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation())
                                          .setEventBus(eventBus)
                                          .buildAndStart();
    }

    /**
     * The {@link PostgresqlDurableQueues} that handles messaging and supports the {@link Inboxes}/{@link Outboxes} implementations
     *
     * @param unitOfWorkFactory                the {@link UnitOfWorkFactory}
     * @param jsonSerializer                   the {@link JSONSerializer} responsible for serializing Message payloads
     * @param optionalMultiTableChangeListener the optional {@link MultiTableChangeListener}
     * @param properties                       the auto configure properties
     * @return the {@link PostgresqlDurableQueues}
     */
    @Bean
    @ConditionalOnMissingBean
    public DurableQueues durableQueues(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                       JSONSerializer jsonSerializer,
                                       Optional<MultiTableChangeListener<TableChangeNotification>> optionalMultiTableChangeListener,
                                       EssentialsComponentsProperties properties,
                                       List<DurableQueuesInterceptor> durableQueuesInterceptors) {
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setMessageHandlingTimeout(properties.getDurableQueues().getMessageHandlingTimeout())
                                                   .setTransactionalMode(properties.getDurableQueues().getTransactionalMode())
                                                   .setJsonSerializer(jsonSerializer)
                                                   .setSharedQueueTableName(properties.getDurableQueues().getSharedQueueTableName())
                                                   .setMultiTableChangeListener(optionalMultiTableChangeListener.orElse(null))
                                                   .setUseCentralizedMessageFetcher(properties.getDurableQueues().isUseCentralizedMessageFetcher())
                                                   .setCentralizedMessageFetcherPollingInterval(properties.getDurableQueues().getCentralizedMessageFetcherPollingInterval())
                                                   .setQueuePollingOptimizerFactory(consumeFromQueue -> new QueuePollingOptimizer.SimpleQueuePollingOptimizer(consumeFromQueue,
                                                                                                                                                              (long) (consumeFromQueue.getPollingInterval().toMillis() *
                                                                                                                                                                      properties.getDurableQueues()
                                                                                                                                                                                .getPollingDelayIntervalIncrementFactor()),
                                                                                                                                                              properties.getDurableQueues()
                                                                                                                                                                        .getMaxPollingInterval()
                                                                                                                                                                        .toMillis()
                                                   )).build();
        durableQueues.addInterceptors(durableQueuesInterceptors);
        return durableQueues;
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableQueuesStatistics durableQueuesStatistics(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                           JSONSerializer jsonSerializer,
                                                           EssentialsComponentsProperties properties) {
        if (properties.getDurableQueues().isEnableQueueStatistics()) {
            return new PostgresqlDurableQueuesStatistics(
                    unitOfWorkFactory,
                    jsonSerializer,
                    properties.getDurableQueues().getSharedQueueTableName(),
                    properties.getDurableQueues().getSharedQueueStatisticsTableName()
            );
        }
        return new NoOpDurableQueuesStatistics();
    }

    @Bean
    @ConditionalOnMissingBean
    public MultiTableChangeListener<TableChangeNotification> multiTableChangeListener(Jdbi jdbi,
                                                                                      JSONSerializer jsonSerializer,
                                                                                      EventBus eventBus,
                                                                                      EssentialsComponentsProperties properties) {
        return new MultiTableChangeListener<>(jdbi,
                                              properties.getMultiTableChangeListener().getPollingInterval(),
                                              jsonSerializer,
                                              eventBus,
                                              properties.getMultiTableChangeListener().isFilterDuplicateNotifications());
    }

    /**
     * The {@link Inboxes} instance using the provided {@link DurableQueues} implementation for message durability and retry
     *
     * @param durableQueues     the {@link DurableQueues} implementation responsible for message durability and retry
     * @param fencedLockManager the distributed locks manager for controlling message consumption across different nodes
     * @return the {@link Inboxes} instance using the provided {@link DurableQueues} implementation for message durability and retry
     */
    @Bean
    @ConditionalOnMissingBean
    public Inboxes inboxes(DurableQueues durableQueues,
                           FencedLockManager fencedLockManager) {
        return Inboxes.durableQueueBasedInboxes(durableQueues,
                                                fencedLockManager);
    }

    /**
     * The {@link Outboxes} instance using the provided {@link DurableQueues} implementation for message durability and retry
     *
     * @param durableQueues     the {@link DurableQueues} implementation responsible for message durability and retry
     * @param fencedLockManager the distributed locks manager for controlling message consumption across different nodes
     * @return the {@link Outboxes} instance using the provided {@link DurableQueues} implementation for message durability and retry
     */
    @Bean
    @ConditionalOnMissingBean
    public Outboxes outboxes(DurableQueues durableQueues,
                             FencedLockManager fencedLockManager) {
        return Outboxes.durableQueueBasedOutboxes(durableQueues,
                                                  fencedLockManager);
    }

    @Bean("essentialsCommandBus")
    @ConditionalOnMissingBean
    public DurableLocalCommandBus commandBus(DurableQueues durableQueues,
                                             UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                                             Optional<QueueName> optionalCommandQueueName,
                                             Optional<RedeliveryPolicy> optionalCommandQueueRedeliveryPolicy,
                                             Optional<SendAndDontWaitErrorHandler> optionalSendAndDontWaitErrorHandler,
                                             List<CommandBusInterceptor> commandBusInterceptors,
                                             EssentialsComponentsProperties properties) {
        var durableCommandBusBuilder = DurableLocalCommandBus.builder()
                                                             .setDurableQueues(durableQueues);
        durableCommandBusBuilder.setParallelSendAndDontWaitConsumers(properties.getReactive().getCommandBusParallelSendAndDontWaitConsumers());
        optionalCommandQueueName.ifPresent(durableCommandBusBuilder::setCommandQueueName);
        optionalCommandQueueRedeliveryPolicy.ifPresent(durableCommandBusBuilder::setCommandQueueRedeliveryPolicy);
        optionalSendAndDontWaitErrorHandler.ifPresent(durableCommandBusBuilder::setSendAndDontWaitErrorHandler);
        durableCommandBusBuilder.addInterceptors(commandBusInterceptors);
        if (commandBusInterceptors.stream().noneMatch(commandBusInterceptor -> UnitOfWorkControllingCommandBusInterceptor.class.isAssignableFrom(commandBusInterceptor.getClass()))) {
            durableCommandBusBuilder.addInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory));
        }
        return durableCommandBusBuilder.build();
    }

    /**
     * Configure the {@link EventBus} to use for all event handlers
     *
     * @param onErrorHandler the error handler which will be called if any asynchronous subscriber/consumer fails to handle an event
     * @param properties     The configuration properties
     * @return the {@link EventBus} to use for all event handlers
     */
    @Bean("essentialsEventBus")
    @ConditionalOnMissingClass("dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus")
    @ConditionalOnMissingBean
    public EventBus eventBus(Optional<OnErrorHandler> onErrorHandler, EssentialsComponentsProperties properties) {
        var localEventBusBuilder = LocalEventBus.builder()
                                                .busName("default")
                                                .overflowMaxRetries(properties.getReactive().getOverflowMaxRetries())
                                                .parallelThreads(properties.getReactive().getEventBusParallelThreads())
                                                .backpressureBufferSize(properties.getReactive().getEventBusBackpressureBufferSize())
                                                .queuedTaskCapFactor(properties.getReactive().getQueuedTaskCapFactor());
        onErrorHandler.ifPresent(localEventBusBuilder::onErrorHandler);
        return localEventBusBuilder.build();
    }

    /**
     * {@link JSONSerializer} responsible for serializing/deserializing the raw Java events to and from JSON
     * (including handling {@link DurableQueues} message payload serialization and deserialization)
     *
     * @param additionalModules additional {@link Module}'s found in the {@link ApplicationContext}
     * @return the {@link JSONSerializer} responsible for serializing/deserializing the raw Java events to and from JSON
     */
    @Bean
    @ConditionalOnMissingClass("dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer")
    @ConditionalOnMissingBean
    public JSONSerializer jsonSerializer(List<Module> additionalModules) {
        var objectMapperBuilder = JsonMapper.builder()
                                            .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                            .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                            .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                            .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                            .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                            .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                            .addModule(new Jdk8Module())
                                            .addModule(new JavaTimeModule());

        additionalModules.forEach(objectMapperBuilder::addModule);

        var objectMapper = objectMapperBuilder.build();
        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                                               .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));

        return new JacksonJSONSerializer(objectMapper);
    }

    /**
     * The {@link LifecycleManager} that handles starting and stopping life cycle beans
     *
     * @param properties the auto configure properties
     * @return the {@link LifecycleManager}
     */
    @Bean
    @ConditionalOnMissingBean
    public LifecycleManager lifecycleController(EssentialsComponentsProperties properties) {
        return new DefaultLifecycleManager(this::onContextRefreshedEvent,
                                           properties.getLifeCycles().isStartLifeCycles());
    }

    private void onContextRefreshedEvent(ApplicationContext applicationContext) {
        var callbacks = applicationContext.getBeansOfType(JdbiConfigurationCallback.class).values();
        if (!callbacks.isEmpty()) {
            var jdbi = applicationContext.getBean(Jdbi.class);
            callbacks.forEach(configureJdbiCallback -> {
                log.info("Calling {}: {}",
                         JdbiConfigurationCallback.class.getSimpleName(),
                         configureJdbiCallback.getClass().getName());
                configureJdbiCallback.configure(jdbi);
            });
        }
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.devtools.restart.RestartScope")
    public SpringBootDevToolsClassLoaderChangeContextRefreshedListener contextRefreshedListener(JSONSerializer jsonSerializer) {
        return new SpringBootDevToolsClassLoaderChangeContextRefreshedListener(jsonSerializer);
    }

    private static class SpringBootDevToolsClassLoaderChangeContextRefreshedListener {
        private static final Logger                log = LoggerFactory.getLogger(SpringBootDevToolsClassLoaderChangeContextRefreshedListener.class);
        private final        JacksonJSONSerializer jacksonJSONSerializer;

        public SpringBootDevToolsClassLoaderChangeContextRefreshedListener(JSONSerializer jsonSerializer) {
            requireNonNull(jsonSerializer, "No jsonSerializer provided");
            this.jacksonJSONSerializer = jsonSerializer instanceof JacksonJSONSerializer ? (JacksonJSONSerializer) jsonSerializer : null;
        }

        @EventListener
        public void handleContextRefresh(ContextRefreshedEvent event) {
            if (jacksonJSONSerializer != null) {
                log.info("Updating the '{}'s internal ObjectMapper's ClassLoader to {} from {}",
                         jacksonJSONSerializer.getClass().getSimpleName(),
                         event.getApplicationContext().getClassLoader(),
                         jacksonJSONSerializer.getObjectMapper().getTypeFactory().getClassLoader()
                        );
                jacksonJSONSerializer.setClassLoader(event.getApplicationContext().getClassLoader());
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public RecordExecutionTimeMessageHandlerInterceptor measurementMessageHandlerInterceptor(EssentialsComponentsProperties properties,
                                                                                             Optional<MeterRegistry> meterRegistry) {
        return new RecordExecutionTimeMessageHandlerInterceptor(meterRegistry,
                                                                properties.getMetrics().getMessageHandler().isEnabled(),
                                                                properties.getMetrics().getMessageHandler().toLogThresholds(),
                                                                properties.getTracingProperties().getModuleTag());
    }

    @Bean
    @ConditionalOnMissingBean
    public RecordExecutionTimeCommandBusInterceptor measurementCommandBusInterceptor(EssentialsComponentsProperties properties,
                                                                                     Optional<MeterRegistry> meterRegistry) {
        return new RecordExecutionTimeCommandBusInterceptor(meterRegistry,
                                                            properties.getMetrics().getCommandBus().isEnabled(),
                                                            properties.getMetrics().getCommandBus().toLogThresholds(),
                                                            properties.getTracingProperties().getModuleTag());
    }

    @Bean
    @ConditionalOnMissingBean
    public RecordExecutionTimeDurableQueueInterceptor measurementDurableQueuesInterceptor(EssentialsComponentsProperties properties,
                                                                                          Optional<MeterRegistry> meterRegistry) {
        return new RecordExecutionTimeDurableQueueInterceptor(meterRegistry,
                                                              properties.getMetrics().getDurableQueues().isEnabled(),
                                                              properties.getMetrics().getDurableQueues().toLogThresholds(),
                                                              properties.getTracingProperties().getModuleTag());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.scheduler", name = "enabled", havingValue = "true")
    public EssentialsScheduler essentialsScheduler(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                   FencedLockManager fencedLockManager,
                                                   EssentialsComponentsProperties properties) {
        return new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, properties.getScheduler().getNumberOfThreads());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EssentialsScheduler.class)
    public PostgresqlTTLManager postgresqlTTLManager(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                     EssentialsScheduler scheduler) {
        return new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PostgresqlTTLManager.class)
    public static TTLJobBeanPostProcessor ttlJobBeanPostProcessor(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        return new TTLJobBeanPostProcessor(beanFactory, environment);
    }

    // Api ###################################################################################################

    @Bean
    @ConditionalOnMissingBean
    public EssentialsSecurityProvider essentialsSecurityProvider() {
        return new EssentialsSecurityProvider.NoAccessSecurityProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public EssentialsAuthenticatedUser essentialsAuthenticatedUser() {
        return new EssentialsAuthenticatedUser.NoAccessAuthenticatedUser();
    }

    @Bean
    @ConditionalOnMissingBean
    public DBFencedLockApi dbFencedLockApi(EssentialsSecurityProvider securityProvider,
                                           PostgresqlFencedLockManager fencedLockManager,
                                           UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        return new DefaultDBFencedLockApi(securityProvider,
                fencedLockManager,
                unitOfWorkFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableQueuesApi durableQueuesApi(EssentialsSecurityProvider securityProvider,
                                             DurableQueues durableQueues,
                                             JSONSerializer jsonSerializer,
                                             DurableQueuesStatistics durableQueuesStatistics) {
        return new DefaultDurableQueuesApi(securityProvider,
                durableQueues,
                jsonSerializer,
                durableQueuesStatistics);
    }

    @Bean
    @ConditionalOnMissingBean
    public PostgresqlQueryStatisticsApi postgresqlQueryStatisticsApi(EssentialsSecurityProvider securityProvider,
                                                                     HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        return new DefaultPostgresqlQueryStatisticsApi(securityProvider,
                unitOfWorkFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.scheduler", name = "enabled", havingValue = "true")
    public SchedulerApi defaultSchedulerApi(EssentialsSecurityProvider securityProvider,
                                     EssentialsScheduler essentialsScheduler) {
        return new DefaultSchedulerApi(essentialsScheduler, securityProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "essentials.scheduler", name = "enabled", havingValue = "false")
    public SchedulerApi noOpSchedulerApi() {
        return new NoOpSchedulerApi();
    }
}
