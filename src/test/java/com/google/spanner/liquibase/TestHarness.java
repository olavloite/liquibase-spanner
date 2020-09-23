/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.spanner.liquibase;

import com.google.cloud.spanner.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class TestHarness {
    private static final Logger logger = LoggerFactory.getLogger(TestHarness.class);

    /*
     * Interface for a Liquibase JDBC Test Harness
     */
    public interface Connection {

        // Return the active JDBC connection.
        //
        // This will reuse JDBC connections between tests.
        java.sql.Connection getJDBCConnection();

        // Stop() stops the test Spanner database and does any needed cleanup.
        void stop() throws SQLException;
    }


    public static String readResource(String resource) {
        InputStream is = TestHarness.class.getClassLoader().getResourceAsStream(resource);
        return new BufferedReader(new InputStreamReader(is))
                .lines()
                .collect(
                        Collectors.joining("\n")
                );
    }

    final private static String INITIAL_SPANNER_SQL = "initial.spanner.sql";
    private static List<String> InitialSQL() {
        return Arrays.asList(
                readResource(INITIAL_SPANNER_SQL)
                        .replaceAll("--.*", "")
                        .split(";"));
    }

    private static void createInstance(Spanner service, String instanceId) throws SQLException {

        if (hasInstance(service, instanceId)) {
            logger.info(String.format("Reusing existing instance %s", instanceId));
            return;
        }

        final String projectId = service.getOptions().getProjectId();
        final InstanceId inst = InstanceId.of(projectId, instanceId);

        // Create it.
        try {
            service.getInstanceAdminClient().createInstance(
                    InstanceInfo.newBuilder(inst)
                            .setDisplayName(" test Instance")
                            .setInstanceConfigId(InstanceConfigId.of(projectId, "emulator-config"))
                            .setNodeCount(1)
                            .build()
            ).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new SQLException("Failed creating instance", e);
        }
    }

    private static boolean hasInstance(Spanner service, String instanceId) {
        for (Instance inst : service.getInstanceAdminClient().listInstances().iterateAll()) {
            if (inst.getId().getInstance().equals(instanceId))
                return true;
        }
        return false;
    }

    private static boolean hasDatabase(Spanner service, String instanceId, String databaseId) {
        for (Database db : service.getDatabaseAdminClient().listDatabases(instanceId).iterateAll()) {
            if (db.getId().getDatabase().equals(databaseId))
                return true;
        }
        return false;
    }

    private static void createDatabase(Spanner service, String instanceId, String databaseId) throws SQLException {
        if (hasDatabase(service, instanceId, databaseId)) {
            logger.info(String.format("Reusing existing instance %s database %s", instanceId, databaseId));
            return;
        }

        try {
            // Create the database
            service.getDatabaseAdminClient().createDatabase(
                instanceId,
                databaseId,
                InitialSQL()
                ).get();

        } catch (ExecutionException | InterruptedException e) {
            throw new SQLException("Unable to create database", e);
        }

        if (!hasDatabase(service, instanceId, databaseId)) {
            throw new SQLException("Failed creating database! Doesn't exist!");
        }

        logger.info(String.format("Created existing instance %s database %s", instanceId, databaseId));
    }

    private static void dropDatabase(Spanner service, String instanceId, String databaseId) throws SQLException {
            service.getDatabaseAdminClient().dropDatabase(
                    instanceId,
                    databaseId
            );
    }


    //
    // Create a Spanner emulator instance
    //
    // Creates a temporary Liquibase database using the Spanner emulator
    //
    public static Connection useSpannerEmulator() throws SQLException {

        // Test parameters
        final String PROJECT_ID = "test-project-id";
        final String INSTANCE_ID = "test-instance-id";
        final String DATABASE_ID = "test-database-id";

        // Use existing emulator or launch a new one
        String spannerEmulatorHost = System.getenv( "SPANNER_EMULATOR_HOST");
        if (spannerEmulatorHost == null) {

            // Create the container
            final String SPANNER_EMULATOR_IMAGE = "gcr.io/cloud-spanner-emulator/emulator:latest";
            final GenericContainer<?> testContainer = new GenericContainer<>(SPANNER_EMULATOR_IMAGE)
                    .withCommand()
                    .withExposedPorts(9010, 9020)
                    .withStartupTimeout(Duration.ofSeconds(10))
                    .waitingFor(Wait.forHttp("/").forStatusCode(404));

            // Start the container
            testContainer.start();

            // JDBC Connection
            spannerEmulatorHost = String.format("%s:%d",
                    testContainer.getContainerIpAddress(),
                    testContainer.getMappedPort(9010));

        }

        // Create the Spanner service
        final Spanner service = SpannerOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setEmulatorHost(String.format("http://%s", spannerEmulatorHost))
                .build()
                .getService();

        // Initialize the instance and database.
        createInstance(service, INSTANCE_ID);
        createDatabase(service, INSTANCE_ID, DATABASE_ID);

        // JDBC Connection
        final java.sql.Connection conn = DriverManager.getConnection(
                String.format(
                        "jdbc:cloudspanner://%s/projects/%s/instances/%s/databases/%s?autocommit=false;usePlainText=true",
                        spannerEmulatorHost, PROJECT_ID, INSTANCE_ID, DATABASE_ID));

        return new Connection() {

            @Override
            public java.sql.Connection getJDBCConnection() {
                return conn;
            }

            @Override
            public void stop() { }
        };
    }

    //
    // Create a normal Spanner connection.
    //
    // Automatically creates and drops a temporary liquibase database
    //
    public static Connection useSpannerConnection() throws SQLException {
        final String projectId = System.getenv("SPANNER_PROJECT");
        final String instanceId = System.getenv("SPANNER_INSTANCE");
        final String databaseId = String.format("test_database_%d", new Random().nextInt(1_000_000));

        // Ensure parameters are properly set
        if (projectId == null || instanceId == null) {
            throw new SQLException("Both SPANNER_PROJECT and SPANNER_INSTANCE environment variables must be set!");
        }

        Spanner service = SpannerOptions.newBuilder()
                .setProjectId(projectId)
                .build()
                .getService();

        createDatabase(service, instanceId, databaseId);

        // JDBC connection initialize
        java.sql.Connection conn = DriverManager.getConnection(
                String.format(
                    "jdbc:cloudspanner:/projects/%s/instances/%s/databases/%s?autocommit=false",
                    projectId, instanceId, databaseId));

        return new Connection() {

            @Override
            public java.sql.Connection getJDBCConnection() {
                return conn;
            }

            @Override
            public void stop() throws SQLException {
                dropDatabase(service, instanceId, databaseId);
                service.close();
            }
        };
    }
}
