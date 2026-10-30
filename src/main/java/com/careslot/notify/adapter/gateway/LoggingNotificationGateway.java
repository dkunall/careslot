package com.careslot.notify.adapter.gateway;

import com.careslot.notify.application.NotificationGateway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Demo gateway: nothing leaves the machine. The "sent" message is written to notification_log, whose unique
 * reminder_id makes a double send fail loudly instead of reaching a patient twice.
 */
@Component
class LoggingNotificationGateway implements NotificationGateway {

    private final JdbcClient jdbc;

    LoggingNotificationGateway(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void send(long reminderId, String channel, String recipient, String body) {
        jdbc.sql("insert into notification_log (reminder_id, channel, recipient, body) values (?, ?, ?, ?)")
                .params(reminderId, channel, recipient, body).update();
    }
}
