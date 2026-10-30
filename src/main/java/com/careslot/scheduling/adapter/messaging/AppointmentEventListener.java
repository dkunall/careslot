package com.careslot.scheduling.adapter.messaging;

import com.careslot.scheduling.application.AvailabilityService;
import com.careslot.scheduling.application.WaitlistService;
import com.careslot.shared.events.AppointmentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Two consumer groups on the appointment topic: one drops the cached day for the doctor on every change, the
 * other offers freed places to the waitlist. Records are keyed by doctor, so one doctor's events arrive in order.
 */
@Component
class AppointmentEventListener {

    private static final Logger log = LoggerFactory.getLogger(AppointmentEventListener.class);

    private final AvailabilityService availability;
    private final WaitlistService waitlist;

    private final ObjectMapper json;

    AppointmentEventListener(AvailabilityService availability, WaitlistService waitlist, ObjectMapper json) {
        this.availability = availability;
        this.waitlist = waitlist;
        this.json = json;
    }

    private AppointmentEvent parse(String payload) {
        try {
            return json.readValue(payload, AppointmentEvent.class);
        } catch (Exception e) {
            log.warn("skipping unreadable appointment event: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    @KafkaListener(topics = AppointmentEvent.TOPIC, groupId = "careslot-availability")
    void invalidate(String payload) {
        AppointmentEvent e = parse(payload);
        if (e != null) availability.evict(e.practitionerId(), e.start());
    }

    @KafkaListener(topics = AppointmentEvent.TOPIC, groupId = "careslot-waitlist")
    void fill(String payload) {
        AppointmentEvent e = parse(payload);
        if (e != null) waitlist.onFreed(e);
    }
}
