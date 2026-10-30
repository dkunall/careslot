package com.careslot.shared.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Consumer-side dedupe. Call inside the consumer's transaction; false means "already handled, skip". */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, UUID eventId) {
        return jdbc.sql("insert into processed_event (consumer, event_id) values (?, ?) on conflict do nothing")
                .params(consumer, eventId).update() == 1;
    }
}
