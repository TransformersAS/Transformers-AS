package com.transformersas.marketplace.sellers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class SellerRegistrationConfigurationTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SellerRegistrationConfiguration.class);

    @Test
    void byDefaultTheTokenIsNotWrittenAnywhere(CapturedOutput output) {
        context.run(app -> {
            assertThat(app).hasSingleBean(EmailVerificationNotifier.class);
            app.getBean(EmailVerificationNotifier.class).notifyVerification("private@example.com", "secret-token");
        });
        assertThat(output.getAll()).doesNotContain("secret-token", "private@example.com");
    }

    @Test
    void developmentModeWritesTheTokenToTheServerLogSoTheFlowCanBeTried(CapturedOutput output) {
        context.withPropertyValues("sellers.verification.log-token=true").run(app ->
                app.getBean(EmailVerificationNotifier.class).notifyVerification("dev@example.com", "dev-token"));
        assertThat(output.getAll()).contains("dev@example.com", "dev-token");
    }

    @Test
    void aRealMailProviderReplacesTheDefault() {
        var provider = mock(EmailVerificationNotifier.class);
        context.withBean(EmailVerificationNotifier.class, () -> provider).run(app -> {
            assertThat(app).hasSingleBean(EmailVerificationNotifier.class);
            assertThat(app.getBean(EmailVerificationNotifier.class)).isSameAs(provider);
            app.getBean(EmailVerificationNotifier.class).notifyVerification("seller@example.com", "token");
        });
        verify(provider).notifyVerification("seller@example.com", "token");
    }
}
