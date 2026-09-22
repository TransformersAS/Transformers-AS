package com.transformersas.marketplace.auth.infrastructure.notification;

import com.transformersas.marketplace.auth.application.port.EmailVerificationNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpEmailVerificationNotifier implements EmailVerificationNotifier {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public SmtpEmailVerificationNotifier(ObjectProvider<JavaMailSender> senders,
            @Value("${app.email-verification.from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    @Override
    public void notifyVerification(String email, String token) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) throw new IllegalStateException("Verification mail is not configured");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Verifica tu correo — Marketplace");
        message.setText("Copia este código en Verificar correo, en el panel de acceso a tu cuenta:\n\n"
                + token + "\n\nVence en 30 minutos y solo puede usarse una vez. Si solicitaste otro, usa el último."
                + "\nSi no creaste esta cuenta, ignora este mensaje.");
        sender.send(message);
    }
}
