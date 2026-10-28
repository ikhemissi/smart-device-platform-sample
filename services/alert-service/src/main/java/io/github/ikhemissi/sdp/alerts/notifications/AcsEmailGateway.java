package io.github.ikhemissi.sdp.alerts.notifications;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import io.github.ikhemissi.sdp.alerts.AlertingProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Azure Communication Services Email with Microsoft Entra authentication (the service's managed identity; access keys
 * are never used). Waits until ACS has accepted the email for delivery.
 */
@Component
public class AcsEmailGateway implements EmailGateway {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(60);

    private final EmailClient client;
    private final String sender;

    public AcsEmailGateway(AlertingProperties properties, TokenCredential credential) {
        AlertingProperties.Email email = properties.email();
        boolean configured = StringUtils.hasText(email.endpoint()) && StringUtils.hasText(email.sender());
        this.client = configured ? new EmailClientBuilder().endpoint(email.endpoint()).credential(credential).buildClient() : null;
        this.sender = email.sender();
    }

    @Override
    public void send(Email email) {
        if (client == null) {
            throw new IllegalStateException("sdp.alerting.email.endpoint and sdp.alerting.email.sender are not configured");
        }
        EmailMessage message = new EmailMessage()
                .setSenderAddress(sender)
                .setToRecipients(email.recipients().stream().map(EmailAddress::new).toList())
                .setSubject(email.subject())
                .setBodyPlainText(email.text())
                .setBodyHtml(email.html());
        SyncPoller<EmailSendResult, EmailSendResult> poller = client.beginSend(message);
        PollResponse<EmailSendResult> response = poller.waitForCompletion(SEND_TIMEOUT);
        if (response.getStatus() != LongRunningOperationStatus.SUCCESSFULLY_COMPLETED) {
            throw new IllegalStateException("email not accepted within " + SEND_TIMEOUT + " (status " + response.getStatus() + ")");
        }
        EmailSendResult result = poller.getFinalResult();
        if (result.getStatus() != EmailSendStatus.SUCCEEDED) {
            throw new IllegalStateException("email " + result.getId() + " " + result.getStatus()
                    + (result.getError() == null ? "" : ": " + result.getError().getMessage()));
        }
    }
}
