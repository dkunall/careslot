package com.careslot.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole app on a random port against real Postgres, Kafka and Redis. Every IT shares this configuration,
 * so Spring caches one context for the run; tests isolate themselves with their own facilities and patients.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ItBase.Security.class)
public abstract class ItBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Security {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api admin;
    protected Api desk;
    protected Api nurse;
    protected Api anon;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        var pg = Containers.postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", () -> Containers.DB_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        r.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        r.add("spring.data.redis.host", () -> Containers.redis().getHost());
        r.add("spring.data.redis.port", () -> Containers.redis().getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("careslot.phi.key", () -> Containers.PHI_KEY);
        // placeholder so one IT class can lower it with @TestPropertySource(properties = "careslot.test.booking-rate=5")
        r.add("careslot.booking.per-patient-per-minute", () -> "${careslot.test.booking-rate:1000}");
        r.add("careslot.reminders.poll", () -> "300ms");
    }

    @BeforeEach
    void clients() {
        anon = Api.at(port);
        admin = anon.as(TestJwt.admin("admin@careslot.test"));
        desk = anon.as(TestJwt.frontDesk("desk@careslot.test"));
        nurse = anon.as(TestJwt.nurse("nurse@careslot.test"));
    }

    protected World world() {
        return new World(this);
    }
}
