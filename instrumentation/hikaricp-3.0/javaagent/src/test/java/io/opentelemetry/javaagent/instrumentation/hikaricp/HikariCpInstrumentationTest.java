/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.hikaricp;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import io.opentelemetry.instrumentation.testing.internal.AutoCleanupExtension;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.db.DbConnectionPoolMetricsAssertions;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.assertj.core.api.AbstractIterableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HikariCpInstrumentationTest {

  @RegisterExtension
  static final InstrumentationExtension testing = AgentInstrumentationExtension.create();

  @RegisterExtension static final AutoCleanupExtension cleanup = AutoCleanupExtension.create();

  @Mock DataSource dataSourceMock;
  @Mock Connection connectionMock;
  @Mock IMetricsTracker userMetricsMock;

  @Test
  void shouldReportMetrics() throws Exception {
    // given
    when(dataSourceMock.getConnection()).thenReturn(connectionMock);

    HikariDataSource hikariDataSource = new HikariDataSource();
    hikariDataSource.setPoolName("testPool");
    hikariDataSource.setDataSource(dataSourceMock);

    // when
    Connection hikariConnection = hikariDataSource.getConnection();
    TimeUnit.MILLISECONDS.sleep(100);
    hikariConnection.close();

    // then
    DbConnectionPoolMetricsAssertions.create(testing, "io.opentelemetry.hikaricp-3.0", "testPool")
        .disableMaxIdleConnections()
        // no timeouts happen during this test
        .disableConnectionTimeouts()
        .assertConnectionPoolEmitsMetrics();

    // when
    hikariDataSource.close();

    // sleep exporter interval
    Thread.sleep(100);
    testing.clearData();
    Thread.sleep(100);

    // then
    testing.waitAndAssertMetrics(
        "io.opentelemetry.hikaricp-3.0",
        "db.client.connections.usage",
        AbstractIterableAssert::isEmpty);
    testing.waitAndAssertMetrics(
        "io.opentelemetry.hikaricp-3.0",
        "db.client.connections.idle.min",
        AbstractIterableAssert::isEmpty);
    testing.waitAndAssertMetrics(
        "io.opentelemetry.hikaricp-3.0",
        "db.client.connections.max",
        AbstractIterableAssert::isEmpty);
    testing.waitAndAssertMetrics(
        "io.opentelemetry.hikaricp-3.0",
        "db.client.connections.pending_requests",
        AbstractIterableAssert::isEmpty);
  }

  @Test
  void shouldNotBreakCustomUserMetrics() throws Exception {
    // given
    when(dataSourceMock.getConnection()).thenReturn(connectionMock);

    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setPoolName("anotherTestPool");
    hikariConfig.setDataSource(dataSourceMock);
    hikariConfig.setMetricsTrackerFactory((poolName, poolStats) -> userMetricsMock);

    HikariDataSource hikariDataSource = new HikariDataSource(hikariConfig);
    cleanup.deferCleanup(hikariDataSource);

    // when
    Connection hikariConnection = hikariDataSource.getConnection();
    TimeUnit.MILLISECONDS.sleep(100);
    hikariConnection.close();

    // then
    DbConnectionPoolMetricsAssertions.create(
            testing, "io.opentelemetry.hikaricp-3.0", "anotherTestPool")
        .disableMaxIdleConnections()
        // no timeouts happen during this test
        .disableConnectionTimeouts()
        .assertConnectionPoolEmitsMetrics();

    verify(userMetricsMock, atLeastOnce()).recordConnectionCreatedMillis(anyLong());
    verify(userMetricsMock, atLeastOnce()).recordConnectionAcquiredNanos(anyLong());
    verify(userMetricsMock, atLeastOnce()).recordConnectionUsageMillis(anyLong());
  }
}
