package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.auth.infrastructure.persistence.repository.JdbcLoginAttemptRepository;
import com.transformersas.marketplace.auth.infrastructure.security.LoginAttemptGuard;
import com.transformersas.marketplace.auth.infrastructure.security.EmailNotVerifiedException;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LoginAttemptGuardIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcLoginAttemptRepository repository;
    @Autowired PlatformTransactionManager manager;
    LoginAttemptGuard guard;
    final AtomicInteger calls = new AtomicInteger();

    static MockEnvironment environment() {
        return new MockEnvironment().withProperty("app.auth.login-attempts.enabled", "true")
                .withProperty("app.auth.login-attempts.origin-verified", "true")
                .withProperty("app.auth.login-attempts.trusted-proxy-addresses", "172.30.238.10")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "\\Q172.30.238.10\\E");
    }
    @BeforeEach void reset() { jdbc.update("DELETE FROM login_attempts"); calls.set(0); guard = new LoginAttemptGuard(environment(), repository); }
    Authentication input(String email, String ip) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(ip);
        request.setAttribute("org.apache.tomcat.request.forwarded", Boolean.TRUE);
        var input = UsernamePasswordAuthenticationToken.unauthenticated(email, "not-logged");
        input.setDetails(guard.details(request)); return input;
    }
    Authentication success() { calls.incrementAndGet(); return UsernamePasswordAuthenticationToken.authenticated("account", null, List.of()); }
    Authentication failure() { calls.incrementAndGet(); throw new BadCredentialsException("generic"); }
    void fail(String email, String ip) { assertThatThrownBy(() -> guard.authenticate(input(email, ip), this::failure)).isInstanceOf(BadCredentialsException.class); }
    void blocked(String email, String ip) {
        assertThatThrownBy(() -> guard.authenticate(input(email, ip), this::success)).isInstanceOfSatisfying(LoginAttemptGuard.Denied.class, e -> assertThat(e.status).isEqualTo(429));
    }
    @Test void fifthFailureBlocksWithoutExtensionAndExpiryStartsNewCycle() {
        for(int i=0;i<4;i++) fail(" Person@Example.com ","8.8.8.8");
        assertThat(jdbc.queryForObject("SELECT blocked_until FROM login_attempts", Long.class)).isNull();
        fail("person@example.com","8.8.8.8");
        long deadline=jdbc.queryForObject("SELECT blocked_until FROM login_attempts",Long.class);
        assertThat(deadline-jdbc.queryForObject("SELECT failure_5 FROM login_attempts",Long.class)).isEqualTo(900000);
        blocked("person@example.com","8.8.8.8"); blocked("person@example.com","8.8.8.8");
        assertThat(calls.get()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT blocked_until FROM login_attempts",Long.class)).isEqualTo(deadline);
        jdbc.update("UPDATE login_attempts SET blocked_until=1");
        fail("person@example.com","8.8.8.8");
        assertThat(jdbc.queryForObject("SELECT failure_2 FROM login_attempts",Long.class)).isNull();
    }
    @Test void rollingWindowDropsOldestButKeepsOtherRecentFailures() {
        for(int i=0;i<4;i++) fail("a","8.8.8.8");
        jdbc.update("UPDATE login_attempts SET failure_1=FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)-900001");
        fail("a","8.8.8.8");
        assertThat(jdbc.queryForObject("SELECT blocked_until FROM login_attempts",Long.class)).isNull();
        fail("a","8.8.8.8"); blocked("a","8.8.8.8");
    }
    @Test void successResetsOnlyItsCombinationAndUnknownIdentifiersAreCounted() {
        for(int i=0;i<4;i++) fail("missing@example.com","8.8.8.8");
        guard.authenticate(input("missing@example.com","1.1.1.1"),this::success);
        guard.authenticate(input("other@example.com","8.8.8.8"),this::success);
        fail("missing@example.com","8.8.8.8"); blocked("missing@example.com","8.8.8.8");
        jdbc.update("UPDATE login_attempts SET blocked_until=1 WHERE blocked_until IS NOT NULL");
        guard.authenticate(input("missing@example.com","8.8.8.8"),this::success);
        for(int i=0;i<4;i++) fail("missing@example.com","8.8.8.8");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE blocked_until IS NOT NULL",Integer.class)).isZero();
    }
    @Test void twoInstancesSerializeConcurrentAttemptsAndPersistAcrossRecreation() throws Exception {
        var second=new LoginAttemptGuard(environment(),new JdbcLoginAttemptRepository(jdbc,manager));
        var start=new CountDownLatch(1); var executor=Executors.newFixedThreadPool(8);
        try {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<8;i++) { final var instance=i%2==0?guard:second; futures.add(executor.submit(()->{
                start.await(); try { instance.authenticate(input("a","8.8.8.8"),this::failure); }
                catch(BadCredentialsException | LoginAttemptGuard.Denied expected) { } return null;
            })); }
            start.countDown(); for(var f:futures) f.get(20,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        assertThat(calls.get()).isEqualTo(5);
        guard=new LoginAttemptGuard(environment(),new JdbcLoginAttemptRepository(jdbc,manager)); blocked("a","8.8.8.8");
    }
    @Test void technicalAndUnverifiedFailuresDoNotCountOrResetAndCredentialFailureSurvivesOuterRollback() {
        fail("a","8.8.8.8");
        assertThatThrownBy(()->guard.authenticate(input("a","8.8.8.8"),()->{throw new EmailNotVerifiedException();})).isInstanceOf(EmailNotVerifiedException.class);
        assertThatThrownBy(()->guard.authenticate(input("a","8.8.8.8"),()->{throw new AuthenticationServiceException("offline");})).isInstanceOf(AuthenticationServiceException.class);
        assertThat(jdbc.queryForObject("SELECT failure_2 FROM login_attempts",Long.class)).isNull();
        new TransactionTemplate(manager).execute(status->{fail("a","8.8.8.8");status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT failure_2 FROM login_attempts",Long.class)).isNotNull();
    }
    @Test void failedDatabaseWriteRollsBackAndDoesNotReturnSuccessfulAuthentication() {
        var broken = spy(jdbc);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated local write failure"))
                .when(broken).update(startsWith("UPDATE login_attempts SET"), any(Object[].class));
        guard = new LoginAttemptGuard(environment(), new JdbcLoginAttemptRepository(broken, manager));
        assertThatThrownBy(()->guard.authenticate(input("a","8.8.8.8"),this::success))
                .isInstanceOfSatisfying(LoginAttemptGuard.Denied.class,e->assertThat(e.status).isEqualTo(503));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts",Integer.class)).isZero();
    }
    @Test void disabledByDefaultDoesNotReadOriginOrWriteDatabase() {
        guard=new LoginAttemptGuard(new MockEnvironment(),repository);
        guard.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("a","x"),this::success);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts",Integer.class)).isZero();
    }
    @Test void mappedIpv4AndRepeatedProviderDelegationCannotEvadeOrDuplicateCounting() {
        var request = input("a", "8.8.8.8");
        assertThatThrownBy(() -> guard.authenticate(request, this::failure)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> guard.authenticate(request, this::failure)).isInstanceOf(BadCredentialsException.class);
        assertThat(calls.get()).isEqualTo(1);
        for (int i=0;i<3;i++) fail("a", "8.8.8.8");
        fail("a", "::ffff:8.8.8.8");
        blocked("a", "8.8.8.8");
    }

    @Test void explicitUnverifiedFrameworkOrBroadProxyConfigurationCannotEnableControl() {
        for (var env : List.of(environment().withProperty("app.auth.login-attempts.origin-verified", "false"),
                environment().withProperty("server.forward-headers-strategy", "framework"),
                environment().withProperty("server.tomcat.remoteip.internal-proxies", ".*"),
                environment().withProperty("server.tomcat.remoteip.trusted-proxies", ".*"),
                environment().withProperty("app.auth.login-attempts.trusted-proxy-addresses", ""))) {
            assertThatThrownBy(() -> new LoginAttemptGuard(env, repository)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void unsafeConfigurationAndUnknownOrGatewayOriginsAreRejectedBeforeCredentials() {
        for(String property:List.of("app.auth.login-attempts.origin-verified","server.forward-headers-strategy","server.tomcat.remoteip.internal-proxies")) {
            var env=environment();env.setProperty(property,"invalid");
            assertThatThrownBy(()->new LoginAttemptGuard(env,repository)).isInstanceOf(RuntimeException.class);
        }
        for(String ip:List.of("172.30.238.1","127.0.0.1","100.64.0.1","::1","fc00::1","unknown","192.0.2.1"))
            assertThatThrownBy(()->guard.authenticate(input("a",ip),this::success)).isInstanceOfSatisfying(LoginAttemptGuard.Denied.class,e->assertThat(e.status).isEqualTo(503));
        var request=new MockHttpServletRequest();request.setRemoteAddr("8.8.8.8");request.addHeader("X-Forwarded-For","1.1.1.1");
        var token=UsernamePasswordAuthenticationToken.unauthenticated("a","x");token.setDetails(guard.details(request));
        assertThatThrownBy(()->guard.authenticate(token,this::success)).isInstanceOf(LoginAttemptGuard.Denied.class);
        guard = new LoginAttemptGuard(environment().withProperty("app.auth.login-attempts.trusted-proxy-addresses", "8.8.8.8")
                .withProperty("server.tomcat.remoteip.internal-proxies", java.util.regex.Pattern.quote("8.8.8.8")), repository);
        assertThatThrownBy(() -> guard.authenticate(input("a", "8.8.8.8"), this::success)).isInstanceOf(LoginAttemptGuard.Denied.class);
        assertThat(calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts",Integer.class)).isZero();
    }
}
