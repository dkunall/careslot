package com.careslot.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** One of each dependency per JVM, started on first use. Credentials and keys are random per run, never printed. */
public final class Containers {

    public static final String DB_PASSWORD = hex(18);
    public static final String PHI_KEY = Base64.getEncoder().encodeToString(bytes(32));

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;

    private Containers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            var pg = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("careslot").withUsername("careslot")
                    .withPassword(DB_PASSWORD).withCommand("postgres", "-c", "max_connections=200");
            pg.start();
            postgres = pg;
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            var k = new KafkaContainer("apache/kafka:3.8.0").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
            k.start();
            kafka = k;
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            GenericContainer<?> r = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            r.start();
            redis = r;
        }
        return redis;
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String hex(int n) {
        return HexFormat.of().formatHex(bytes(n));
    }
}
