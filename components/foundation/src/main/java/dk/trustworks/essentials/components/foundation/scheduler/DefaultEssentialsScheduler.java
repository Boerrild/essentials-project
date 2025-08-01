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

package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorScheduledJobRepository.ExecutorJobEntry;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.PgCronRepository.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.Lifecycle;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.shared.network.Network;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * <b>Note: This scheduler is not intended to replace a full-fledged scheduler such as Quartz or Spring, it is a simple
 * scheduler that utilizes the postgresql pg_cron extension if available or a simple {@link ScheduledExecutorService} to schedule jobs.
 * It is meant to be used internally by essentials components to schedule jobs!
 * </b>
 * <p>
 * {@link DefaultEssentialsScheduler} is a task scheduler implementation that manages scheduling for both
 * PostgreSQL-based cron jobs (<code>pg_cron</code>) and standard Java Executor-based jobs. The class ensures
 * proper execution of tasks based on available locking mechanisms and PostgreSQL support.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Schedules and manages both Executor-based and pg_cron-based jobs.</li>
 *   <li>Coordinates job scheduling with the availability of PostgreSQL's pg_cron extension.</li>
 *   <li>Manages task lifecycle using a distributed lock to ensure coordinated task execution across multiple nodes.</li>
 * </ul>
 */
public class DefaultEssentialsScheduler implements EssentialsScheduler, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(DefaultEssentialsScheduler.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final FencedLockManager                                             fencedLockManager;
    private final int                                                           schedulerThreads;
    private       boolean                                                       pgCronAvailable;
    private final LockName                                                      lockName;

    private volatile boolean started;
    private volatile boolean lockAcquired;

    private       ScheduledExecutorService       executorService;
    private final PgCronRepository               pgCronRepository;
    private final ExecutorScheduledJobRepository executorScheduledJobRepository;

    private final List<PgCronJob>         pgCronJobs   = new CopyOnWriteArrayList<>();
    private final Map<PgCronJob, Integer> pgCronJobIds = new ConcurrentHashMap<>();

    private final List<ExecutorJob>                    executorJobs       = new CopyOnWriteArrayList<>();
    private final Map<ExecutorJob, ScheduledFuture<?>> executorJobFutures = new ConcurrentHashMap<>();

    public DefaultEssentialsScheduler(HandleAwareUnitOfWorkFactory<?> unitOfWorkFactory,
                                      FencedLockManager lockManager,
                                      int schedulerThreads) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.fencedLockManager = requireNonNull(lockManager, "lockManager cannot be null");
        requireTrue(schedulerThreads > 0, "schedulerThreads must be greater than 0");
        this.schedulerThreads = schedulerThreads;
        this.pgCronRepository = new PgCronRepository(unitOfWorkFactory);
        this.executorScheduledJobRepository = new ExecutorScheduledJobRepository(unitOfWorkFactory);
        this.lockName = new LockName("essentials-scheduler");
    }

    /**
     * Schedules a PostgreSQL cron job using the {@link PgCronJob} details provided.
     * If the scheduler is started, pg_cron is available, and the necessary lock is acquired,
     * the job will be scheduled internally. Otherwise, the job will be added for later scheduling.
     * <p>
     * {@link PgCronJob#functionName()} is validated by {@link PgCronRepository} using {@link PostgresqlUtil#isValidFunctionName(String)} as an initial layer
     * of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
     * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     * <br>
     * It is highly recommended that the {@link PgCronJob#cronExpression()}, {@link PgCronJob#functionName()} and  {@link PgCronJob#args()} value is only derived from a controlled and trusted source.<br>
     * To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@link PgCronJob#cronExpression()}, {@link  PgCronJob#functionName()} and  {@link PgCronJob#args()} values.<br>
     *
     * @param job the {@link PgCronJob} instance containing details about the job to be scheduled;
     *            must not be null.
     */
    @Override
    public void schedulePgCronJob(PgCronJob job) {
        requireNonNull(job, "job cannot be null");
        if (pgCronJobs.contains(job)) {
            log.info("PgCronJob '{}' already exists'", job);
            return;
        }
        log.debug("Scheduling PgCronJob '{}'", job);
        pgCronJobs.add(job);
        if (started && pgCronAvailable && lockAcquired) {
            schedulePgCronJobInternal(job);
        } else {
            if (started && !pgCronAvailable) {
                log.warn("Can't schedule job - Scheduler is started but pg_cron is not available: '{}'", job);
            } else {
                log.info("Can't schedule job right now - Scheduler is started '{}' pg_cron is available '{}' and lock acquired '{}': '{}'", started, pgCronAvailable, lockAcquired, job);
            }
        }
    }

    @Override
    public void scheduleExecutorJob(ExecutorJob job) {
        requireNonNull(job, "job cannot be null");
        if (executorJobs.contains(job)) {
            log.info("ExecutorJob '{}' already exists", job);
            return;
        }
        log.debug("Adding ExecutorJob '{}'", job);
        executorJobs.add(job);
        if (started && lockAcquired) {
            scheduleExecutorJobInternal(job);
        } else {
            log.info("Can't schedule job right now - Scheduler is started '{}' and lock acquired '{}': '{}'", started, lockAcquired, job);
        }
    }

    @Override
    public boolean isPgCronAvailable() {
        return pgCronAvailable;
    }

    @Override
    public LockName getLockName() {
        return lockName;
    }

    /**
     * Experimental
     */
    public boolean cancelPgCronJob(Integer jobId) {
        log.debug("Cancelling PgCronJob '{}'", jobId);
        try {
            pgCronRepository.unschedule(jobId);
            findJobById(jobId).ifPresent(job -> {
                pgCronJobIds.remove(job);
                pgCronJobs.remove(job);
            });
            return true;
        } catch (Exception e) {
            log.warn("Failed to unschedule pg_cron jobId {}", jobId, e);
        }
        return false;
    }

    /**
     * Experimental
     */
    public boolean cancelExecutorJob(String name) {
        log.debug("Cancelling ExecutorJob '{}'", name);
        try {
            var result = executorScheduledJobRepository.deleteByName(name);
            if (result) {
                findJobByName(name).ifPresent(job -> {
                    executorJobFutures.remove(job);
                    executorJobs.remove(job);
                });
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to cancel executor job {}", name, e);
        }
        return false;
    }

    private void scheduleExecutorJobInternal(ExecutorJob job) {
        if (executorScheduledJobRepository.existsByName(job.name())) {
            log.warn("ExecutorJob '{}' already exists", job);
            return;
        }

        Runnable safeTask = () -> {
            try {
                job.task().run();
            } catch (Throwable t) {
                log.warn("❌ ExecutorJob '{}' threw an exception, but will retry on next interval", job, t);
            }
        };

        var future = executorService.scheduleAtFixedRate(
                safeTask,
                job.fixedDelay().initialDelay(),
                job.fixedDelay().period(),
                job.fixedDelay().unit());
        log.info("✅ Added ExecutorJob '{}'", job);
        executorJobFutures.put(job, future);
        executorScheduledJobRepository.insert(job);
    }

    private void schedulePgCronJobInternal(PgCronJob job) {
        if (pgCronRepository.doesJobExist(job.name()) == null) {
            var jobId = pgCronRepository.schedule(job);
            if (jobId != null) {
                log.info("✅ Added PgCronJob '{}' with jobId '{}'", job, jobId);
                pgCronJobIds.put(job, jobId);
            }
        } else {
            log.warn("PgCronJob '{}' already exists", job);
        }
    }

    private Optional<PgCronJob> findJobById(Integer jobId) {
        return pgCronJobIds.entrySet().stream()
                           .filter(e -> e.getValue().equals(jobId))
                           .map(Map.Entry::getKey)
                           .findFirst();
    }

    private Optional<ExecutorJob> findJobByName(String name) {
        return executorJobFutures.keySet().stream()
                                 .filter(job -> job.name().equals(name))
                                 .findFirst();
    }

    @Override
    public void start() {
        if (!started) {
            started = true;

            executorService = Executors.newScheduledThreadPool(schedulerThreads,
                                                               ThreadFactoryBuilder.builder()
                                                                                   .nameFormat("essentials-scheduler-%d")
                                                                                   .daemon(true)
                                                                                   .build()
                                                              );

            unitOfWorkFactory.usingUnitOfWork(uow -> {
                var available = PostgresqlUtil.isPGExtensionAvailable(uow.handle(), "pg_cron");
                if (available) {
                    uow.handle().execute("CREATE EXTENSION IF NOT EXISTS pg_cron;");
                    boolean loaded = determineIfPgCronIsLoaded();
                    pgCronAvailable = loaded;
                    if (!loaded) {
                        log.warn("Detected that pg_cron exists but is not shared_preload_libraries‐loaded; disabling pg_cron support.");
                    }
                } else {
                    pgCronAvailable = false;
                }
            });
            log.info("⚙️ Starting Essentials Scheduler - pg_cron available = '{}' & '{}' local executor threads", pgCronAvailable, schedulerThreads);

            deleteJobsWithInstanceId();

            fencedLockManager.acquireLockAsync(lockName,
                                               LockCallback.builder()
                                                           .onLockAcquired(this::onLockAcquired)
                                                           .onLockReleased(this::onLockReleased)
                                                           .build());
        }
    }

    private boolean determineIfPgCronIsLoaded() {
        try {
            Integer testId = pgCronRepository.schedule(new PgCronJob("test",
                                                                     "test",
                                                                     null,
                                                                     CronExpression.ONE_SECOND));
            pgCronRepository.unschedule(testId);
        } catch (Exception e) {
            var notLoaded = PostgresqlUtil.isPGExtensionNotLoadedException(e);
            if (!notLoaded) {
                log.warn("Failed to determine if pg_cron is loaded", e);
            }
            return false;
        }
        return true;
    }

    private void onLockAcquired(FencedLock lock) {
        log.info("🎉 FencedLock '{}' was ACQUIRED, lockAcquired '{}' purging stale entries, then scheduling all jobs.", lockName, lockAcquired);


        deleteJobsWithInstanceId();

        scheduleJobs();

        lockAcquired = true;
    }

    private void deleteJobsWithInstanceId() {
        var instanceId = Network.hostName();

        if (pgCronAvailable) {
            try {
                pgCronRepository.deleteJobByNameEndingWithInstanceId(instanceId);
            } catch (Exception e) {
                if (IOExceptionUtil.isIOException(e)) {
                    log.debug("Failed to purge stale pg_cron jobs", e);
                } else {
                    log.warn("Failed to purge stale pg_cron jobs", e);
                }
            }
        }

        try {
            executorScheduledJobRepository.deleteByNameEndingWithInstanceId(instanceId);
        } catch (Exception e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug("Failed to purge stale executor scheduled jobs", e);
            } else {
                log.warn("Failed to purge stale executor scheduled jobs", e);
            }

        }
    }

    private void onLockReleased(FencedLock lock) {
        log.info("🚨 FencedLock '{}' was RELEASED; unscheduling all pg_cron and executor tasks immediately.", lockName);
        lockAcquired = false;

        var instanceId = Network.hostName();

        unschedulePgCronJobs(instanceId);

        unscheduleExecutorJobs(instanceId);

        try {
            executorScheduledJobRepository.deleteAll();
        } catch (Exception e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug("Failed to purge stale executor scheduled jobs on lock release", e);
            } else {
                log.warn("Failed to purge stale executor scheduled jobs on lock release", e);
            }
        }
    }

    private void scheduleJobs() {
        if (pgCronAvailable) {
            log.info("Scheduling '{}' pg_cron jobs", pgCronJobs.size());
            for (PgCronJob job : pgCronJobs) {
                schedulePgCronJobInternal(job);
            }
        }

        log.info("Scheduling '{}' executor jobs", executorJobs.size());
        for (var job : executorJobs) {
            scheduleExecutorJobInternal(job);
        }
    }

    @Override
    public void stop() {
        if (started) {
            started = false;
            log.info("⏹ Stopping Essentials Scheduler (pg_cron available = '{}')", pgCronAvailable);

            if (lockAcquired) {
                try {
                    executorScheduledJobRepository.deleteAll();
                } catch (Exception e) {
                    if (IOExceptionUtil.isIOException(e)) {
                        log.debug("Error deleting executor scheduled jobs in stop()", e);
                    } else {
                        log.warn("Error deleting executor scheduled jobs in stop()", e);
                    }
                }
            }

            fencedLockManager.cancelAsyncLockAcquiring(lockName);

            var instanceId = Network.hostName();

            unschedulePgCronJobs(instanceId);

            unscheduleExecutorJobs(instanceId);

            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }

            log.info("🛑 Stopped Essentials Scheduler");
        }
    }

    private void unscheduleExecutorJobs(String instanceId) {
        for (var future : executorJobFutures.values()) {
            future.cancel(true);
        }
        try {
            executorScheduledJobRepository.deleteByNameEndingWithInstanceId(instanceId);
        } catch (Exception e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug("Failed to purge executor scheduled jobs for instance '{}'", instanceId, e);
            } else {
                log.warn("Failed to purge executor scheduled jobs for instance '{}'", instanceId, e);
            }
        }
        executorJobFutures.clear();
    }

    private void unschedulePgCronJobs(String instanceId) {
        if (pgCronAvailable) {
            for (Map.Entry<PgCronJob, Integer> pair : pgCronJobIds.entrySet()) {
                var jobId = pair.getValue();
                if (jobId != null) {
                    try {
                        pgCronRepository.unschedule(jobId);
                    } catch (Exception e) {
                        if (IOExceptionUtil.isIOException(e)) {
                            log.debug("Failed to unschedule pg_cron jobId '{}'", jobId, e);
                        } else {
                            log.warn("Failed to unschedule pg_cron jobId '{}'", jobId, e);
                        }
                    }
                }
            }
            try {
                pgCronRepository.deleteJobByNameEndingWithInstanceId(instanceId);
            } catch (Exception e) {
                if (IOExceptionUtil.isIOException(e)) {
                    log.debug("Failed to purge pg_cron jobs for instance '{}'", instanceId, e);
                } else {
                    log.warn("Failed to purge pg_cron jobs for instance '{}'", instanceId, e);
                }
            }
            pgCronJobIds.clear();
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    public List<PgCronEntry> fetchPgCronEntries(long startIndex, long pageSize) {
        if (pgCronAvailable) {
            return pgCronRepository.fetchPgCronEntries(startIndex, pageSize);

        }
        return Collections.emptyList();
    }

    public long getTotalPgCronEntries() {
        if (pgCronAvailable) {
            return pgCronRepository.getTotalPgCronEntries();

        }
        return 0L;
    }

    public List<PgCronJobRunDetails> fetchPgCronJobRunDetails(Integer jobId, long startIndex, long pageSize) {
        if (pgCronAvailable) {
            return pgCronRepository.fetchPgCronJobDetails(jobId, startIndex, pageSize);
        }
        return Collections.emptyList();
    }

    public long getTotalPgCronJobRunDetails(Integer jobId) {
        if (pgCronAvailable) {
            return pgCronRepository.getTotalPgCronJobDetails(jobId);
        }
        return 0L;
    }

    public List<ExecutorJobEntry> fetchExecutorJobEntries(long startIndex, long pageSize) {
        return executorScheduledJobRepository.fetchExecutorJobEntries(pageSize, startIndex, true);
    }

    public long getTotalExecutorJobEntries() {
        return executorScheduledJobRepository.getTotalExecutorJobEntries();
    }

}
