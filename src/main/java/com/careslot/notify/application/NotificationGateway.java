package com.careslot.notify.application;

/** Where reminder texts go. The demo gateway records them; a deployment plugs in SMS or e-mail here. */
public interface NotificationGateway {

    void send(long reminderId, String channel, String recipient, String body);
}
