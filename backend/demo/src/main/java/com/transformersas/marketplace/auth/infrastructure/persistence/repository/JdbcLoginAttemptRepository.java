package com.transformersas.marketplace.auth.infrastructure.persistence.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcLoginAttemptRepository {
    private static final long WINDOW = 900_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcLoginAttemptRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Result(Authentication authentication, AuthenticationException failure, long retrySeconds) {}

    public Result authenticate(byte[] key, Supplier<Authentication> authenticate) {
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO login_attempts(attempt_key) VALUES (?) ON DUPLICATE KEY UPDATE attempt_key=attempt_key", key);
            var state = jdbc.queryForMap("SELECT * FROM login_attempts WHERE attempt_key=? FOR UPDATE", key);
            long now = now();
            Number blocked = (Number) state.get("blocked_until");
            if (blocked != null && blocked.longValue() > now) {
                return new Result(null, null, (blocked.longValue() - now + 999) / 1000);
            }
            List<Long> failures = new ArrayList<>();
            if (blocked == null) {
                for (int i = 1; i <= 5; i++) {
                    Number value = (Number) state.get("failure_" + i);
                    if (value != null) failures.add(value.longValue());
                }
            }
            try {
                Authentication result = authenticate.get();
                if (result == null || !result.isAuthenticated()) {
                    throw new org.springframework.security.authentication.AuthenticationServiceException("Authentication did not complete");
                }
                save(key, List.of(), null);
                return new Result(result, null, 0);
            } catch (BadCredentialsException | UsernameNotFoundException | AccountStatusException failure) {
                long failedAt = now();
                failures.removeIf(time -> time <= failedAt - WINDOW);
                failures.add(failedAt);
                save(key, failures, failures.size() >= 5 ? failedAt + WINDOW : null);
                // Return the exception, then throw it outside the committed transaction.
                return new Result(null, failure, 0);
            }
        });
    }

    private long now() {
        return jdbc.queryForObject("SELECT FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)", Long.class);
    }

    private void save(byte[] key, List<Long> failures, Long blocked) {
        jdbc.update("UPDATE login_attempts SET failure_1=?,failure_2=?,failure_3=?,failure_4=?,failure_5=?,blocked_until=? WHERE attempt_key=?",
                at(failures, 0), at(failures, 1), at(failures, 2), at(failures, 3), at(failures, 4), blocked, key);
    }

    private static Long at(List<Long> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }
}
