package com.careslot.shared.events;

import com.careslot.shared.config.CareSlotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed outbox rows to Kafka. Rows are claimed with SKIP LOCKED so several app instances can relay
 * without publishing the same row twice in the normal case; a crash between send and mark republishes,
 * which is why every consumer dedupes on event id.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String topic, String key, String payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batch;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, CareSlotProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batch = props.outbox().batch();
    }

    @Scheduled(fixedDelayString = "${careslot.outbox.poll}")
    void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(s -> publishBatch());
            } while (sent != null && sent == batch);
        } catch (RuntimeException e) {
            log.warn("outbox relay failed, will retry: {}", e.toString());
        }
    }

    private int publishBatch() {
        List<Row> rows = jdbc.sql("""
                select id, topic, msg_key, payload::text from outbox where published_at is null
                order by id limit ? for update skip locked""")
                .param(batch)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        for (Row r : rows) {
            try {
                kafka.send(r.topic(), r.key(), r.payload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("kafka send failed for outbox " + r.id(), e);
            }
        }
        if (!rows.isEmpty()) {
            jdbc.sql("update outbox set published_at = now() where id = any(?)")
                    .param(rows.stream().mapToLong(Row::id).boxed().toArray(Long[]::new)).update();
        }
        return rows.size();
    }
}
