package com.edgeguardian.controller;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final DockerImageName TIMESCALE_IMAGE =
        DockerImageName.parse("timescale/timescaledb:latest-pg18")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection
    static PostgreSQLContainer DB = new PostgreSQLContainer(TIMESCALE_IMAGE)
        .withReuse(true);

}
