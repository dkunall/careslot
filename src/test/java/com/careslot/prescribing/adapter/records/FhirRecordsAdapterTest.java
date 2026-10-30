package com.careslot.prescribing.adapter.records;

import com.careslot.prescribing.application.ClinicalRecordsPort.RecordsUnavailableException;
import com.careslot.prescribing.domain.PatientContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirRecordsAdapterTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger delayMs = new AtomicInteger(0);
    private final List<String> queries = new CopyOnWriteArrayList<>();

    private static final String MEDS = """
            {"resourceType":"Bundle","entry":[
              {"resource":{"resourceType":"MedicationStatement","status":"active","medicationCodeableConcept":{"coding":[
                {"system":"urn:careslot:drug","code":"RX-WARF"},{"system":"http://example.test/other","code":"X"}]}}}]}""";
    private static final String ALLERGIES = """
            {"resourceType":"Bundle","entry":[
              {"resource":{"resourceType":"AllergyIntolerance","code":{"coding":[{"system":"urn:careslot:drug-class","code":"penicillin"}]}}},
              {"resource":{"resourceType":"AllergyIntolerance","code":{"coding":[{"system":"urn:careslot:drug","code":"RX-IBUP"}]}}}]}""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/fhir", ex -> {
            queries.add(ex.getRequestURI().toString());
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String body = ex.getRequestURI().getPath().endsWith("MedicationStatement") ? MEDS : ALLERGIES;
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private FhirRecordsAdapter adapter(Duration timeout) {
        return new FhirRecordsAdapter("http://127.0.0.1:" + server.getAddress().getPort() + "/fhir/", timeout,
                new ObjectMapper());
    }

    @Test
    void readsActiveMedicinesAndAllergiesByHealthId() {
        PatientContext ctx = adapter(Duration.ofSeconds(2)).read(UUID.randomUUID(), "CS-TEST-0001");
        assertThat(ctx.activeMedications()).containsExactly("RX-WARF");
        assertThat(ctx.allergies()).containsExactlyInAnyOrder("class:PENICILLIN", "RX-IBUP");
        assertThat(queries).hasSize(2).allSatisfy(q -> assertThat(java.net.URLDecoder.decode(q, StandardCharsets.UTF_8))
                .contains("patient.identifier=urn:careslot:health-id|CS-TEST-0001"));
        assertThat(queries.get(0)).contains("status=active");
        assertThat(queries.get(1)).contains("clinical-status=active");
    }

    @Test
    void serverErrorIsUnavailable() {
        status.set(500);
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).read(UUID.randomUUID(), "CS-TEST-0001"))
                .isInstanceOf(RecordsUnavailableException.class).hasMessageContaining("500");
    }

    @Test
    void slowServerTimesOutAsUnavailable() {
        delayMs.set(1500);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> adapter(Duration.ofMillis(300)).read(UUID.randomUUID(), "CS-TEST-0001"))
                .isInstanceOf(RecordsUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1400));
    }
}
