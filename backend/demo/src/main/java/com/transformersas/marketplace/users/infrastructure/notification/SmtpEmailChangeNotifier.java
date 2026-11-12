package com.transformersas.marketplace.users.infrastructure.notification;

import com.transformersas.marketplace.users.application.port.EmailChangeNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpEmailChangeNotifier implements EmailChangeNotifier {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    public SmtpEmailChangeNotifier(ObjectProvider<JavaMailSender> senders,@Value("${app.email-verification.from:}") String from) { this.senders=senders;this.from=from; }
    @Override public void send(String email,String token) {
        var sender=senders.getIfAvailable();
        if(sender==null || from.isBlank()) throw new IllegalStateException("Correo de cambio de dirección no configurado");
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);
        message.setSubject("Confirma tu nuevo correo — Marketplace");
        message.setText("Copia este código en Mi perfil para confirmar el cambio de correo:\n\n"+token+
            "\n\nVence en 30 minutos, es de un solo uso y sustituye los códigos anteriores. El correo actual permanece vigente hasta confirmar. Si no solicitaste el cambio, ignora este mensaje.");
        sender.send(message);
    }
}
