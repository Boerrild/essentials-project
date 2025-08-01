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

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;


/**
 * When running it locally set env variable PGCRON_IMAGE=lcramontw/postgres-with-pg-cron:latest
 */
@Testcontainers
public class EssentialsSchedulerIT_WithPgCron extends AbstractEssentialsSchedulerTest {

    @Container
    PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(pgCronImage)
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_cron", "-c", "cron.database_name=test-db")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Override
    protected PostgreSQLContainer<?> getPostgreSQLContainer() {
        return postgreSQLContainer;
    }

    @Test
    public void schedule_with_1_node() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        var     fencedLockManager = new TestFencedLockManager(jdbi);
        fencedLockManager.start();
        var essentialsScheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 2);
        essentialsScheduler.start();

        assertThat(essentialsScheduler.isPgCronAvailable()).isTrue();
        waitAtMost(Duration.ofSeconds(5)).until(() ->
                                                        fencedLockManager.isLockAcquired(essentialsScheduler.getLockName())

                                               );
        long cronCount = essentialsScheduler.getTotalPgCronEntries();
        long execCount = essentialsScheduler.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(0);
        assertThat(execCount).isEqualTo(0);

        setupTestData(unitOfWorkFactory);
        int rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);
        setupTestFunction(unitOfWorkFactory);

        var cron = CronExpression.TEN_SECOND;
        essentialsScheduler.schedulePgCronJob(new PgCronJob("test", TEST_FUNCTION_NAME, null, cron));
        waitAtMost(Duration.ofSeconds(30)).until(() -> {
            int rows = getNumberOfRowsInTable(unitOfWorkFactory);
            return rows == 15;
        });

        cronCount = essentialsScheduler.getTotalPgCronEntries();
        execCount = essentialsScheduler.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(1);
        assertThat(execCount).isEqualTo(0);

        fencedLockManager.stop();
        essentialsScheduler.stop();

        assertThat(essentialsScheduler.getTotalExecutorJobEntries()).isEqualTo(0);
        waitAtMost(Duration.ofSeconds(5)).until(() -> !fencedLockManager.isLockAcquired(essentialsScheduler.getLockName()));
    }

    @Test
    public void schedule_with_2_nodes() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        var fencedLockManager1 = new TestFencedLockManager(jdbi);
        fencedLockManager1.start();
        var fencedLockManager2 = new TestFencedLockManager(jdbi);
        fencedLockManager2.start();

        var essentialsScheduler1 = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager1, 2);
        var essentialsScheduler2 = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager2, 2);

        essentialsScheduler1.start();
        essentialsScheduler2.start();

        assertThat(essentialsScheduler1.isPgCronAvailable()).isTrue();
        assertThat(essentialsScheduler2.isPgCronAvailable()).isTrue();
        waitAtMost(Duration.ofSeconds(5)).until(() ->
                                                        fencedLockManager1.isLockAcquired(essentialsScheduler1.getLockName()) ^
                                                                fencedLockManager2.isLockAcquired(essentialsScheduler2.getLockName())
                                               );

        long cronCount1 = essentialsScheduler1.getTotalPgCronEntries();
        long execCount1 = essentialsScheduler1.getTotalExecutorJobEntries();
        assertThat(cronCount1).isEqualTo(0);
        assertThat(execCount1).isEqualTo(0);
        long cronCount2 = essentialsScheduler2.getTotalPgCronEntries();
        long execCount2 = essentialsScheduler2.getTotalExecutorJobEntries();
        assertThat(cronCount2).isEqualTo(0);
        assertThat(execCount2).isEqualTo(0);

        setupTestData(unitOfWorkFactory);
        int rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);
        setupTestFunction(unitOfWorkFactory);

        var cron = CronExpression.TEN_SECOND;
        essentialsScheduler1.schedulePgCronJob(new PgCronJob("test", TEST_FUNCTION_NAME, null, cron));
        essentialsScheduler2.schedulePgCronJob(new PgCronJob("test", TEST_FUNCTION_NAME, null, cron));
        waitAtMost(Duration.ofSeconds(30)).until(() -> {
            int rows = getNumberOfRowsInTable(unitOfWorkFactory);
            return rows == 15;
        });

        cronCount1 = essentialsScheduler1.getTotalPgCronEntries();
        execCount1 = essentialsScheduler1.getTotalExecutorJobEntries();
        assertThat(cronCount1).isEqualTo(1);
        assertThat(execCount1).isEqualTo(0);

        essentialsScheduler1.stop();
        fencedLockManager1.stop();
        essentialsScheduler2.stop();
        fencedLockManager2.stop();
    }

    @Test
    public void schedule_with_2_nodes_failover() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        setupTestData(unitOfWorkFactory);
        setupTestFunction(unitOfWorkFactory);

        var fencedLockManager1 = new TestFencedLockManager(jdbi);
        fencedLockManager1.start();
        var essentialsScheduler1 =
                new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager1, 2);
        essentialsScheduler1.schedulePgCronJob(
                new PgCronJob("test", TEST_FUNCTION_NAME, null, CronExpression.TEN_SECOND)
                                              );

        var fencedLockManager2 = new TestFencedLockManager(jdbi);
        fencedLockManager2.start();
        var essentialsScheduler2 =
                new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager2, 2);
        essentialsScheduler2.schedulePgCronJob(
                new PgCronJob("test", TEST_FUNCTION_NAME, null, CronExpression.TEN_SECOND)
                                              );

        essentialsScheduler1.start();
        essentialsScheduler2.start();

        waitAtMost(Duration.ofSeconds(5)).until(() ->
                                                        fencedLockManager1.isLockAcquired(essentialsScheduler1.getLockName())
                                               );

        waitAtMost(Duration.ofSeconds(30)).until(() ->
                                                         getNumberOfRowsInTable(unitOfWorkFactory) == 15
                                                );

        long cronCount = essentialsScheduler1.getTotalPgCronEntries();
        long execCount = essentialsScheduler1.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(1);
        assertThat(execCount).isEqualTo(0);

        essentialsScheduler1.stop();
        fencedLockManager1.stop();

        waitAtMost(Duration.ofSeconds(10)).until(() ->
                                                         fencedLockManager2.isLockAcquired(essentialsScheduler2.getLockName())
                                                );

        waitAtMost(Duration.ofSeconds(30)).until(() ->
                                                         getNumberOfRowsInTable(unitOfWorkFactory) == 25
                                                );

        cronCount = essentialsScheduler2.getTotalPgCronEntries();
        execCount = essentialsScheduler2.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(1);
        assertThat(execCount).isEqualTo(0);

        essentialsScheduler2.stop();
        fencedLockManager2.stop();
    }

}
