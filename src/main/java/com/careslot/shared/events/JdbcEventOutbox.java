package com.careslot.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcEventOutbox implements EventOutbox {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcEventOutbox(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AppointmentEvent event) {
        try {
            jdbc.sql("insert into outbox (event_id, topic, msg_key, payload) values (?, ?, ?, ?::jsonb)")
                    .params(event.eventId(), AppointmentEvent.TOPIC, event.practitionerId().toString(),
                            json.writeValueAsString(event))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
