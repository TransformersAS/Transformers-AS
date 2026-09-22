package com.transformersas.marketplace.auth.infrastructure.notification;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Uses the shared SMTP connection, with a separate sender, message and recovery token lifecycle. */
public final class SmtpPasswordRecoveryNotifier implements PasswordRecoveryNotifier {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public SmtpPasswordRecoveryNotifier(ObjectProvider<JavaMailSender> senders, String from) {
        this.senders = senders;
        this.from = from;
    }

    @Override
    public void notifyRecovery(String email, String token) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) throw new IllegalStateException("Recovery mail is not configured");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Recupera tu contraseña — Marketplace");
        message.setText("Para recuperar tu contraseña, abre el panel de acceso a tu cuenta y selecciona "
                + "«Ya tengo un token de recuperación». Copia este código en «Token de recuperación recibido» "
                + "y elige una nueva contraseña:\n\n" + token
                + "\n\nVence en 15 minutos y solo puede usarse una vez. Si solicitaste otro, usa el último."
                + "\nSi no solicitaste recuperar tu contraseña, ignora este mensaje.");
        sender.send(message);
    }
}
