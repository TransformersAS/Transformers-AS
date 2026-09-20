package com.transformersas.marketplace.auth.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManageAccountSessionsTests {
    private static final String CONTEXT = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<MapSession> repository = mock(FindByIndexNameSessionRepository.class);
    private final ManageAccountSessions service = new ManageAccountSessions(repository);
    private final AccountPrincipal owner = principal(1L);
    private final Map<String, MapSession> indexed = new LinkedHashMap<>();

    @Test
    void listsOnlyAuthenticatedUnexpiredOwnedSessionsAndHandlesUnlimitedExpiry() {
        var current = session("current", owner);
        current.setMaxInactiveInterval(Duration.ofSeconds(-1));
        var other = session("other", owner);
        // Deliberately reverse insertion order; equal creation times use the session id as tiebreaker.
        Instant created = Instant.now().minusSeconds(60);
        current.setCreationTime(created);
        other.setCreationTime(created);
        indexed.clear();
        indexed.put(other.getId(), other);
        indexed.put(current.getId(), current);
        addInvalidSessions();
        stubIndex();
        var result = service.list(owner, current.getId());
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().current()).isTrue();
        assertThat(result.getFirst().createdAt()).isEqualTo(created);
        assertThat(result.getFirst().expiresAt()).isNull();
        assertThat(result.getLast().current()).isFalse();
        assertThat(result.getLast().lastAccessedAt()).isEqualTo(other.getLastAccessedTime());
        assertThat(result.getLast().expiresAt()).isEqualTo(other.getLastAccessedTime().plus(other.getMaxInactiveInterval()));
        assertThat(result).allSatisfy(summary -> assertThat(summary.id()).matches("[a-f0-9]{64}"));
        assertThat(result).extracting(ManageAccountSessions.SessionSummary::id).doesNotContain("current", "other");
        verify(repository, never()).deleteById(anyString());
    }

    @Test
    void revokeOperationsNeverDeleteForeignExpiredOrUnauthenticatedSessions() {
        session("current", owner);
        session("other", owner);
        addInvalidSessions();
        stubIndex();
        service.revokeOthers(owner, "current");
        verify(repository).deleteById("other");
        verify(repository, times(1)).deleteById(anyString());
        clearInvocations(repository);
        service.revokeAll(owner);
        verify(repository).deleteById("current");
        verify(repository).deleteById("other");
        verify(repository, times(2)).deleteById(anyString());
    }

    @Test
    void revokeUsesManagementIdAndIdentifiesCurrentSession() {
        session("current", owner);
        session("other", owner);
        stubIndex();
        var summaries = service.list(owner, "current");
        for (var summary : summaries) {
            assertThat(service.revoke(owner, summary.id(), "current")).isEqualTo(summary.current());
        }
        verify(repository).deleteById("current");
        verify(repository).deleteById("other");
        clearInvocations(repository);
        assertThatThrownBy(() -> service.revoke(owner, "current", "current"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        verify(repository, never()).deleteById(anyString());
    }

    private void addInvalidSessions() {
        var expired = session("expired", owner);
        expired.setLastAccessedTime(Instant.now().minus(Duration.ofHours(2)));
        session("foreign", principal(2L));
        session("missing-context", owner).removeAttribute(CONTEXT);
        session("wrong-context", owner).setAttribute(CONTEXT, "not a security context");
        session("missing-authentication", owner).setAttribute(CONTEXT, new SecurityContextImpl());
        session("unauthenticated", owner).setAttribute(CONTEXT,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.unauthenticated(owner, null)));
        session("wrong-principal", owner).setAttribute(CONTEXT,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("external-principal", null, List.of())));
    }
    private MapSession session(String id, AccountPrincipal principal) {
        var session = new MapSession(id);
        session.setAttribute(CONTEXT, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities())));
        indexed.put(id, session);
        return session;
    }
    private void stubIndex() {
        when(repository.findByPrincipalName(owner.getUsername())).thenReturn(indexed);
    }
    private static AccountPrincipal principal(long id) {
        // Same email index deliberately tests that account id, not the index alone, proves ownership.
        return new AccountPrincipal(new UserAccount(id, "person@example.com", "$2a$04$" + "a".repeat(53),
                AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
    }
}
