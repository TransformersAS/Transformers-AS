package com.transformersas.marketplace.auth.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
public class ManageAccountSessions {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public ManageAccountSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public record SessionSummary(String id, Instant createdAt, Instant lastAccessedAt,
                                 Instant expiresAt, boolean current) {}

    public List<SessionSummary> list(AccountPrincipal account, String currentSessionId) {
        return ownedSessions(account).stream()
                .sorted(Comparator.comparing(Session::getCreationTime).thenComparing(Session::getId))
                .map(session -> new SessionSummary(publicId(session), session.getCreationTime(),
                        session.getLastAccessedTime(), session.getMaxInactiveInterval().isNegative() ? null
                                : session.getLastAccessedTime().plus(session.getMaxInactiveInterval()),
                        session.getId().equals(currentSessionId)))
                .toList();
    }

    /** Returns whether the caller must also invalidate the current servlet session. */
    public boolean revoke(AccountPrincipal account, String id, String currentSessionId) {
        Session target = ownedSessions(account).stream().filter(session -> publicId(session).equals(id))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sesión no encontrada"));
        sessions.deleteById(target.getId());
        return target.getId().equals(currentSessionId);
    }

    public void revokeOthers(AccountPrincipal account, String currentSessionId) {
        ownedSessions(account).stream()
                .filter(session -> !session.getId().equals(currentSessionId))
                .forEach(session -> sessions.deleteById(session.getId()));
    }

    public void revokeAll(AccountPrincipal account) {
        ownedSessions(account).forEach(session -> sessions.deleteById(session.getId()));
    }

    private List<? extends Session> ownedSessions(AccountPrincipal account) {
        return sessions.findByPrincipalName(account.getUsername()).values().stream()
                .filter(session -> !session.isExpired())
                .filter(session -> {
                    Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    return value instanceof SecurityContext context && context.getAuthentication() != null
                            && context.getAuthentication().isAuthenticated()
                            && context.getAuthentication().getPrincipal() instanceof AccountPrincipal owner
                            && account.accountId().equals(owner.accountId());
                }).toList();
    }

    /** The management identifier must never expose the session's bearer credential. */
    private static String publicId(Session session) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(session.getId().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
