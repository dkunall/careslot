package com.careslot.shared.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    @Bean
    NewTopic appointmentsTopic() {
        // keyed by practitioner, so one doctor's events stay in order
        return TopicBuilder.name(AppointmentEvent.TOPIC).partitions(3).replicas(1).build();
    }

    /** Retry a failing record with backoff for about a minute, then log and skip it. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxElapsedTime(60_000);
        return new DefaultErrorHandler(backOff);
    }
}
