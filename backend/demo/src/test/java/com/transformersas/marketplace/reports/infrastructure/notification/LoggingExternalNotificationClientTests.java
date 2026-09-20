package com.transformersas.marketplace.reports.infrastructure.notification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class LoggingExternalNotificationClientTests {
    @Test
    void warnsAboutMissingProviderWithoutLoggingPrivatePayload(CapturedOutput output) {
        new LoggingExternalNotificationClient().send("recipient-42", "INFORMATION_REQUESTED", "{\"private\":\"confidential-evidence\"}");
        assertThat(output.getOut()).contains("Servicio externo de notificaciones no configurado", "INFORMATION_REQUESTED", "recipient-42")
                .doesNotContain("confidential-evidence");
    }
}
