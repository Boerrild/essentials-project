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

package dk.trustworks.essentials.components.foundation.postgresql.ttl;

import dk.trustworks.essentials.components.foundation.scheduler.EssentialsScheduler;
import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorJob;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.PgCronJob;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.ttl.*;
import dk.trustworks.essentials.shared.*;
import org.slf4j.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.*;
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;

/**
 * Manages TTL (Time-To-Live) jobs in a PostgreSQL environment. This class schedules and executes
 * TTL jobs, which are defined as per the {@link TTLJobDefinition}, ensuring periodic cleanups
 * or operations on database tables based on user-defined schedules.
 * <p>
 * Implements the {@link TTLManager} interface for managing TTL jobs and the {@link Lifecycle}
 * interface for controlled starting and stopping behavior.
 * <p>
 * <b>SECURITY WARNING - Limited Validation of provided {@link TTLJobDefinition#action()} (such as {@link DefaultTTLJobAction}) value validations:</b><br>
 * This implementation provides <b>only partial protection</b> against SQL injection:
 * <ul>
 * <li><b>VALIDATED:</b> {@link DefaultTTLJobAction#tableName} - checked for valid SQL identifier format as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input<br>
 * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
 * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br></li>
 * <li><b>NOT VALIDATED:</b> {@code DefaultTTLJobAction#whereClause} and {@link DefaultTTLJobAction#fullDeleteSql} provided to {@link DefaultTTLJobAction} - these are executed directly without any security checks</li>
 * </ul>
 * <p>
 * <b>Developer Responsibility:</b><br>
 * You MUST ensure that the provided {@link TTLJobAction}, such as {@link DefaultTTLJobAction}'s {@code tableName}, {@code whereClause} and {@code fullDeleteSql} values, are safe before creating
 * this object. These values will be executed directly by the {@link TTLManager} with no additional
 * validation or sanitization.
 * <p>
 * <b>Security Best Practices:</b>
 * <ul>
 * <li>Only derive {@link TTLJobAction} values, such as {@link DefaultTTLJobAction}'s {@code tableName}, {@code whereClause} and {@code fullDeleteSql} from controlled, trusted sources</li>
 * <li>Never allow external or untrusted input to directly provide these values</li>
 * <li>Implement your own validation/sanitization before passing these parameters</li>
 * <li>Consider using parameterized queries or prepared statements where possible</li>
 * </ul>
 * <p>
 * <b>Failure to properly validate unprotected parameters may result in SQL injection vulnerabilities
 * that could compromise database security and integrity.</b>
 */
public class PostgresqlTTLManager implements TTLManager, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(PostgresqlTTLManager.class);

    private final EssentialsScheduler                                           scheduler;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final List<TTLJobDefinition>                                        ttlJobDefinitions = new CopyOnWriteArrayList<>();

    private volatile     boolean started;

    public PostgresqlTTLManager(EssentialsScheduler scheduler,
                                HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.scheduler = requireNonNull(scheduler, "scheduler must not be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
    }

    /**
     * Schedules a Time-To-Live (TTL) job for execution. The job defines actions to manage
     * data lifecycle based on expiration policies and scheduling configurations. If the manager
     * is already started, the job will be immediately scheduled; otherwise, it will be
     * added to a queue for scheduling upon startup.
     * <p>
     * See class security note related to {@link TTLJobDefinition#action()} (see {@link DefaultTTLJobAction}) values provided.
     * To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide {@link TTLJobAction} values, such as the
     * {@code DefaultTTLJobAction#tableName}, {@code DefaultTTLJobAction#whereClause} and {@code DefaultTTLJobAction#fullDeleteSql} values.
     *
     * @param jobDefinition the TTL job definition containing the action and scheduling configuration. Must not be null.
     */
    @Override
    public void scheduleTTLJob(TTLJobDefinition jobDefinition) {
        requireNonNull(jobDefinition, "jobDefinition must not be null");
        if (ttlJobDefinitions.contains(jobDefinition)) {
            log.info("TTL job '{}' already scheduled", jobDefinition);
            return;
        }
        log.debug("Scheduling TTL job '{}'", jobDefinition);
        ttlJobDefinitions.add(jobDefinition);
        if (started) {
            log.info("Scheduling TTL job '{}'", jobDefinition);
            scheduleJob(jobDefinition);
        } else {
            log.info("Manager not started, ttl job '{}' will be scheduled when manager is started", jobDefinition);
        }
    }

    private void scheduleJob(TTLJobDefinition jobDefinition) {
        TTLJobAction action = jobDefinition.action();
        ScheduleConfiguration scheduleConfig = jobDefinition.scheduleConfiguration();
        action.validate(unitOfWorkFactory);

        if (scheduleConfig instanceof CronScheduleConfiguration cronConfig) {
            if (scheduler.isPgCronAvailable()) {
                scheduler.schedulePgCronJob(new PgCronJob(action.jobName(),
                                                          action.functionCall().functionName(),
                                                          action.functionCall().args(),
                                                          cronConfig.cronExpression()));
                return;
            }
            log.warn("PgCron not available, falling back to fixed-delay scheduling.");
        }

        FixedDelayScheduleConfiguration fixedConfig;
        if (scheduleConfig instanceof FixedDelayScheduleConfiguration fdc) {
            fixedConfig = fdc;
        } else if (scheduleConfig instanceof CronScheduleConfiguration csc) {
            fixedConfig = csc.fixedDelay()
                             .map(FixedDelayScheduleConfiguration::new)
                             .orElseGet(csc::toFixedDelayConfiguration);
        } else {
            throw new IllegalArgumentException(msg("Unsupported schedule configuration type '{}'", scheduleConfig.getClass().getSimpleName()));
        }

        Runnable runnable = () -> action.executeDirectly(unitOfWorkFactory);
        scheduler.scheduleExecutorJob(
                new ExecutorJob(action.jobName(),
                        fixedConfig.fixedDelay(),
                        runnable)
                                               );
    }

    @Override
    public void start() {
        if (!started) {
            started = true;
            log.info("⚙️ Starting Postgresql Time-to-Live manager");

            initializeTimeToLiveFunction();

            log.info("Scheduling '{}' TTL job definitions", ttlJobDefinitions.size());
            for (TTLJobDefinition jobDefinition : ttlJobDefinitions) {
                scheduleJob(jobDefinition);
            }
        }
    }

    private void initializeTimeToLiveFunction() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            String sql = bind("""
                            CREATE OR REPLACE FUNCTION {:functionName}
                            (p_table_name text, p_delete_statement text) RETURNS void AS $$
                            BEGIN
                            -- Use format/identifier quoting to guard against SQL injection
                            EXECUTE format('DELETE FROM %I WHERE %s', p_table_name, p_delete_statement);
                            END;\
                            $$ LANGUAGE plpgsql;""", arg("functionName", DEFAULT_TTL_FUNCTION_NAME));
            uow.handle().execute(sql);
        });
    }

    @Override
    public void stop() {
        if (started) {
            started = false;
            log.info("🛑 Stopped Postgresql Time-to-Live manager");
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    public static String shortHash(String s) {
        try {
            MessageDigest md     = MessageDigest.getInstance("MD5");
            byte[]        digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
