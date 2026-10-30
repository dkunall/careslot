package com.careslot.it;

import com.careslot.notify.application.ReminderWorker;
import com.careslot.support.ItBase;
import com.careslot.support.Race;
import com.careslot.support.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReminderIT extends ItBase {

    @Autowired
    ReminderWorker worker;

    @Test
    void dueRemindersAreSentExactlyOnceEvenWithOverlappingWorkers() {
        World w = world();
        World.Person dr = w.doctor("Dr Reminder");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "17:00", 15, 1);
        Instant start = World.at(World.today().plusDays(3), "10:00");
        List<UUID> patients = new ArrayList<>();
        List<String> appts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            World.Person p = w.patient();
            patients.add(p.id());
            Instant s = start.plusSeconds(900L * i);
            appts.add(desk.post("/v1/appointments", w.consult(p.id(), dr.id(), s, s.plusSeconds(900)), key("rem-" + i))
                    .expect(201).text("id"));
        }
        String inList = "('" + String.join("','", appts) + "')";
        assertThat(jdbc.queryForObject("select count(*) from reminder where status = 'PENDING' and appointment_id in "
                + inList, Integer.class)).isEqualTo(12);

        // the last appointment is cancelled: its reminders must never go out
        desk.post("/v1/appointments/" + appts.get(5) + "/cancel", Map.of("reason", "patient called")).expect(200);
        jdbc.update("update reminder set due_at = now() - interval '1 minute' where appointment_id in " + inList);

        List<Integer> sent = Race.run(6, i -> worker.runOnce());
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "select count(*) from reminder where status = 'PENDING' and appointment_id in " + inList,
                Integer.class) == 0);

        assertThat(jdbc.queryForObject("select count(*) from reminder where status = 'SENT' and appointment_id in "
                + inList, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from reminder where status = 'CANCELLED' and appointment_id in "
                + inList, Integer.class)).isEqualTo(2);
        List<Map<String, Object>> log = jdbc.queryForList("""
                select n.channel, n.recipient, n.body from notification_log n join reminder r on r.id = n.reminder_id
                where r.appointment_id in """ + inList);
        assertThat(log).hasSize(10);
        assertThat(sent.stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(10);
        assertThat(log).allSatisfy(row -> {
            assertThat(row.get("channel")).isEqualTo("SMS");
            assertThat((String) row.get("recipient")).isEqualTo("********0000").doesNotContain("+91");
            String body = (String) row.get("body");
            assertThat(body).contains(w.facility.replace("fac-", "Synthetic Hospital ")).contains("Asia/Kolkata")
                    .doesNotContain("Dr Reminder").doesNotContain("Test Patient").doesNotContain("General Medicine");
        });
        assertThat(log.stream().map(r -> (String) r.get("body")).filter(b -> b.contains("10:00 AM"))).hasSize(2);
    }
}
