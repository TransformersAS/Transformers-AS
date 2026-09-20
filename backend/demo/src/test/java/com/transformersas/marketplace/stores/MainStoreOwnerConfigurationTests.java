package com.transformersas.marketplace.stores;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase;
import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase.Outcome;
import com.transformersas.marketplace.stores.infrastructure.config.MainStoreOwnerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El runner de arranque: usa la propiedad, avisa en el log sin el correo y nunca impide el arranque. */
class MainStoreOwnerConfigurationTests {

    private static final String EMAIL = "secreto.vendedor@example.com";

    private final AssignMainStoreOwnerUseCase useCase = mock(AssignMainStoreOwnerUseCase.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MainStoreOwnerConfiguration.class)
            .withBean(AssignMainStoreOwnerUseCase.class, () -> useCase);

    private final Logger logger = (Logger) LoggerFactory.getLogger(MainStoreOwnerConfiguration.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void restoreLogs() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    private void runWith(String... properties) {
        context.withPropertyValues(properties)
                .run(app -> app.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments()));
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void withoutThePropertyTheUseCaseReceivesAnEmptyEmailAndNothingIsWarned() {
        when(useCase.execute("")).thenReturn(Outcome.NOT_CONFIGURED);

        runWith();

        verify(useCase).execute("");
        assertThat(messages(Level.WARN)).isEmpty();
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void thePropertyIsPassedToTheUseCaseAndASuccessIsInformed() {
        when(useCase.execute(EMAIL)).thenReturn(Outcome.ASSIGNED);

        runWith("stores.main-store-owner-email=" + EMAIL);

        verify(useCase).execute(EMAIL);
        assertThat(messages(Level.INFO)).singleElement().asString().contains("quedó asignada");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void anAlreadyOwnedStoreIsInformedNotWarned() {
        when(useCase.execute(EMAIL)).thenReturn(Outcome.ALREADY_HAS_OWNER);

        runWith("stores.main-store-owner-email=" + EMAIL);

        assertThat(messages(Level.INFO)).singleElement().asString().contains("ya tiene dueña");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void everyProblemIsWarnedWithAnActionableMessageAndNeverWithTheEmail() {
        for (Outcome problem : List.of(Outcome.STORE_NOT_FOUND, Outcome.ACCOUNT_NOT_FOUND, Outcome.ACCOUNT_NOT_SELLER,
                Outcome.ACCOUNT_OWNS_ANOTHER_STORE)) {
            when(useCase.execute(EMAIL)).thenReturn(problem);
            assertThatCode(() -> runWith("stores.main-store-owner-email=" + EMAIL)).doesNotThrowAnyException();
        }

        assertThat(messages(Level.WARN)).hasSize(4).allSatisfy(message -> assertThat(message).contains("MAIN_STORE_OWNER_EMAIL"));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(EMAIL));
    }

    @Test
    void aFailureIsWarnedAndDoesNotPreventStartup() {
        when(useCase.execute(EMAIL)).thenThrow(new IllegalStateException("base de datos caída " + EMAIL));

        assertThatCode(() -> runWith("stores.main-store-owner-email=" + EMAIL)).doesNotThrowAnyException();

        assertThat(messages(Level.WARN)).singleElement().asString()
                .contains("el arranque continúa", "IllegalStateException").doesNotContain(EMAIL);
    }
}
