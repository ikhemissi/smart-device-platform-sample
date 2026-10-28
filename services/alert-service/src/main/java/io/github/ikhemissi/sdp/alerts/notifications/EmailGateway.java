package io.github.ikhemissi.sdp.alerts.notifications;

import java.util.List;

/** Sends one email; throws when it was not accepted for delivery (the caller retries). */
@FunctionalInterface
public interface EmailGateway {

    record Email(List<String> recipients, String subject, String text, String html) {
    }

    void send(Email email);
}
