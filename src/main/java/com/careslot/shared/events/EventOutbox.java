package com.careslot.shared.events;

/** Appends an event in the caller's transaction; the relay publishes it to Kafka after commit. */
public interface EventOutbox {

    void append(AppointmentEvent event);
}
