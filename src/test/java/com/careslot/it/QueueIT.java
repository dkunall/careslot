package com.careslot.it;

import com.careslot.support.Api;
import com.careslot.support.ItBase;
import com.careslot.support.Race;
import com.careslot.support.World;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;

class QueueIT extends ItBase {

    /** A doctor with 5-minute, 30-patient slots all day, and the next start time (always under 2h away). */
    private record Clinic(World world, World.Person doctor, Instant start) {
    }

    private Clinic clinic(String name) {
        World w = world();
        World.Person dr = w.doctor(name);
        w.template(dr.id(), "FREQ=DAILY", "00:00", "23:55", 5, 30);
        for (LocalDate d : List.of(World.today(), World.today().plusDays(1))) {
            JsonNode starts = desk.get("/v1/practitioners/" + dr.id() + "/availability?date=" + d).expect(200)
                    .body().path("starts");
            if (!starts.isEmpty()) {
                return new Clinic(w, dr, Instant.parse(starts.get(0).asText()));
            }
        }
        throw new IllegalStateException("no slot");
    }

    private List<String> book(Clinic c, int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(desk.post("/v1/appointments", c.world().consult(c.world().patient().id(), c.doctor().id(), c.start(),
                    c.start().plusSeconds(300)), key("q" + i)).expect(201).text("id"));
        }
        return ids;
    }

    @Test
    void concurrentCheckInsGetDistinctConsecutiveNumbersAndRepeatsReturnTheSameToken() {
        Clinic c = clinic("Dr Queue Numbers");
        List<String> appts = book(c, 20);
        List<Api.Response> r = Race.run(20, i -> desk.post("/v1/appointments/" + appts.get(i) + "/check-in", Map.of()));
        assertThat(r).allSatisfy(x -> assertThat(x.status()).isEqualTo(201));
        assertThat(r.stream().map(x -> x.body().path("number").asInt()).sorted().toList())
                .isEqualTo(IntStream.rangeClosed(1, 20).boxed().toList());

        List<String> more = book(c, 1);
        List<Api.Response> same = Race.run(8, i -> nurse.post("/v1/appointments/" + more.get(0) + "/check-in", Map.of()));
        assertThat(same.stream().filter(x -> x.status() == 201)).hasSize(1);
        assertThat(same.stream().filter(x -> x.status() == 200)).hasSize(7);
        assertThat(same.stream().map(x -> x.body().path("number").asInt()).distinct().toList()).containsExactly(21);
        assertThat(desk.get("/v1/appointments/" + more.get(0)).expect(200).text("status")).isEqualTo("CHECKED_IN");
        c.doctor().api().post("/v1/appointments/" + more.get(0) + "/check-in", Map.of()).expect(403);
    }

    @Test
    void onlyOnePatientIsCalledAtATimeAndCompletingFeedsTheEta() throws Exception {
        Clinic c = clinic("Dr Queue Calls");
        UUID doctor = c.doctor().id();
        assertThat(nurse.post("/v1/practitioners/" + doctor + "/queue/call-next", Map.of()).status()).isEqualTo(204);
        List<String> appts = book(c, 3);
        for (String a : appts) {
            desk.post("/v1/appointments/" + a + "/check-in", Map.of()).expect(201);
        }
        List<Api.Response> calls = Race.run(10, i -> (i % 2 == 0 ? nurse : c.doctor().api())
                .post("/v1/practitioners/" + doctor + "/queue/call-next", Map.of()));
        assertThat(calls.stream().filter(x -> x.status() == 200)).hasSize(1);
        assertThat(calls.stream().filter(x -> x.status() == 409)).hasSize(9);
        Api.Response called = calls.stream().filter(x -> x.status() == 200).findFirst().orElseThrow();
        assertThat(called.body().path("number").asInt()).isEqualTo(1);
        String first = called.text("appointmentId");
        assertThat(desk.get("/v1/appointments/" + first).expect(200).text("status")).isEqualTo("IN_CONSULT");

        JsonNode board = desk.get("/v1/practitioners/" + doctor + "/queue?date=" + LocalDate.ofInstant(c.start(),
                World.ZONE)).expect(200).body();
        assertThat(board.path("tokens")).hasSize(3);
        assertThat(board.path("tokens").get(2).path("ahead").asInt()).isEqualTo(1);
        assertThat(board.toString()).doesNotContain("name");

        World.Person stranger = c.world().doctor("Dr Elsewhere");
        String note = "Synthetic note: mild cough, advised fluids";
        stranger.api().post("/v1/appointments/" + first + "/complete", Map.of("note", note)).expect(403);
        nurse.post("/v1/appointments/" + first + "/complete", Map.of("note", note)).expect(403);
        Thread.sleep(1100);
        c.doctor().api().post("/v1/appointments/" + first + "/complete", Map.of("note", note)).expect(200);
        c.doctor().api().post("/v1/appointments/" + first + "/complete", Map.of()).expect(409);
        assertThat(desk.get("/v1/appointments/" + first).expect(200).text("status")).isEqualTo("COMPLETED");

        byte[] stored = jdbc.queryForObject("select note_enc from consult_note where appointment_id = ?::uuid",
                byte[].class, first);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("cough");
        assertThat(jdbc.queryForObject("select count(*) from phi_access where action = 'consult-note:write' and actor = ?",
                Integer.class, c.doctor().subject())).isEqualTo(1);
        // clamped to the 60 s floor; with one sample the EWMA is that sample
        assertThat(jdbc.queryForObject("select samples from consult_stats where practitioner_id = ?", Long.class, doctor))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select mean_seconds from consult_stats where practitioner_id = ?", Double.class,
                doctor)).isEqualTo(60.0);

        Api.Response next = c.doctor().api().post("/v1/practitioners/" + doctor + "/queue/call-next", Map.of()).expect(200);
        assertThat(next.body().path("number").asInt()).isEqualTo(2);
        JsonNode eta = desk.get("/v1/appointments/" + appts.stream().filter(a -> !a.equals(first)
                && !a.equals(next.text("appointmentId"))).findFirst().orElseThrow() + "/queue-eta").expect(200).body();
        assertThat(eta.path("number").asInt()).isEqualTo(3);
        assertThat(eta.path("ahead").asInt()).isZero();
        assertThat(eta.path("meanConsultSeconds").asLong()).isEqualTo(60);
        assertThat(eta.path("etaSeconds").asLong()).isBetween(0L, 60L);
    }

    @Test
    void cancelledAppointmentsCannotBeCheckedIn() {
        Clinic c = clinic("Dr Queue Cancel");
        String a = book(c, 1).get(0);
        desk.post("/v1/appointments/" + a + "/cancel", Map.of()).expect(200);
        assertThat(desk.post("/v1/appointments/" + a + "/check-in", Map.of()).expect(409).text("code"))
                .isEqualTo("invalid-state");
    }
}
