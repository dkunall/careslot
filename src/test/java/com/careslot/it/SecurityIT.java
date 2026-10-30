package com.careslot.it;

import com.careslot.support.Api;
import com.careslot.support.ItBase;
import com.careslot.support.TestJwt;
import com.careslot.support.World;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs in its own context with a low booking rate so the limiter actually trips. */
@TestPropertySource(properties = "careslot.test.booking-rate=5")
class SecurityIT extends ItBase {

    @Test
    void bookingAttemptsPerPatientAreThrottledWithRetryAfter() {
        World w = world();
        World.Person dr = w.doctor("Dr Rate");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "17:00", 15, 1);
        Instant start = World.at(World.today().plusDays(2), "09:00");
        World.Person pt = w.patient();
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Instant s = start.plusSeconds(900L * i);
            codes.add(desk.post("/v1/appointments", w.consult(pt.id(), dr.id(), s, s.plusSeconds(900)), key("rl-" + i))
                    .status());
        }
        // the limit counts attempts in the current minute; a minute boundary mid-loop can reset it once
        assertThat(codes.stream().filter(c -> c == 429).count()).isBetween(1L, 3L);
        assertThat(codes.subList(0, 5)).allSatisfy(c -> assertThat(c).isEqualTo(201));
        Api.Response limited = desk.post("/v1/appointments", w.consult(pt.id(), dr.id(), start.plusSeconds(9000),
                start.plusSeconds(9900)), key("rl-x"));
        if (limited.status() == 429) {
            assertThat(limited.text("code")).isEqualTo("rate-limited");
            assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        }
        World.Person other = w.patient();
        desk.post("/v1/appointments", w.consult(other.id(), dr.id(), start.plusSeconds(12600), start.plusSeconds(13500)),
                key("rl-other")).expect(201);
    }

    @Test
    void tokensWithoutACareSlotRoleOrSignatureAreRejected() {
        anon.get("/v1/drug-catalog").expect(401);
        anon.as("not-a-jwt").get("/v1/drug-catalog").expect(401);
        anon.as(TestJwt.token("someone@example.test", "openid")).get("/v1/drug-catalog").expect(403);
        String good = TestJwt.patient("someone@example.test");
        String tampered = good.substring(0, good.length() - 4) + (good.endsWith("AAAA") ? "BBBB" : "AAAA");
        anon.as(tampered).get("/v1/drug-catalog").expect(401);
        assertThat(anon.get("/actuator/health").expect(200).text("status")).isEqualTo("UP");
        assertThat(anon.get("/actuator/env").status()).isIn(401, 403, 404);
        assertThat(anon.get("/fhir/r4/metadata").status()).isEqualTo(401);
        JsonNode cat = anon.as(good).get("/v1/drug-catalog").expect(200).body();
        assertThat(cat.path("note").asText()).contains("not clinically validated");
    }

    @Test
    void patientDataIsEncryptedAtRestAndEveryReadIsAudited() {
        World w = world();
        World.Person pt = w.patient();
        // login_subject is the IdP's subject (an opaque id in a real IdP; the test IdP reuses the e-mail), so it is
        // left out; everything identifying in the remaining columns must be ciphertext
        Map<String, Object> row = jdbc.queryForMap("""
                select health_id_hash, health_id_enc, name_enc, phone_enc, email_enc from patient where id = ?""", pt.id());
        String dump = row.values().stream().map(v -> v instanceof byte[] b ? new String(b) : String.valueOf(v))
                .reduce("", String::concat);
        assertThat(dump).doesNotContain("Test Patient").doesNotContain(pt.subject()).doesNotContain("+910000000000");
        int before = jdbc.queryForObject("select count(*) from phi_access where patient_id = ?", Integer.class, pt.id());
        pt.api().get("/v1/patients/" + pt.id()).expect(200);
        nurse.get("/v1/patients/" + pt.id()).expect(200);
        w.patient().api().get("/v1/patients/" + pt.id()).expect(403);
        assertThat(jdbc.queryForObject("select count(*) from phi_access where patient_id = ?", Integer.class, pt.id()))
                .isEqualTo(before + 2);
    }
}
