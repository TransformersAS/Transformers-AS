package com.transformersas.marketplace.users;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class UserAccountPersistenceTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("accounts_test").withUsername("test").withPassword("test");

    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearAccounts() {
        jdbc.update("DELETE FROM user_account_roles");
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void persistsAndReloadsAccountThroughDomainPort() {
        UserAccount saved = accounts.save(account(" Person@Example.com ", Set.of(Role.COMPRADOR)));
        assertThat(saved.id()).isPositive();
        UserAccount loaded = accounts.findById(saved.id()).orElseThrow();
        assertThat(loaded.email()).isEqualTo("person@example.com");
        assertThat(loaded.status()).isEqualTo(AccountStatus.ACTIVA);
        assertThat(accounts.findByEmail(" PERSON@EXAMPLE.COM ")).contains(loaded);
        assertThat(jdbc.queryForObject("SELECT email FROM user_accounts WHERE id=?", String.class, saved.id()))
                .isEqualTo("person@example.com");
    }

    @Test
    void databaseEnforcesUniqueEmail() {
        accounts.save(account("person@example.com", Set.of(Role.COMPRADOR)));
        assertThatThrownBy(() -> accounts.save(account("PERSON@example.com", Set.of(Role.VENDEDOR))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account_roles", Integer.class)).isEqualTo(1);
    }

    @Test
    void persistsMultipleRolesForOneAccount() {
        Set<Role> roles = Set.of(Role.COMPRADOR, Role.VENDEDOR, Role.ADMIN, Role.SOPORTE);
        UserAccount saved = accounts.save(account("roles@example.com", roles));
        assertThat(accounts.findById(saved.id()).orElseThrow().roles()).containsExactlyInAnyOrderElementsOf(roles);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account_roles WHERE account_id=?",
                Integer.class, saved.id())).isEqualTo(4);
    }

    @Test
    void storesOnlyBcryptAndVerifiesPassword() {
        String raw = "OnlyForThisTest!";
        assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
        UserAccount saved = accounts.save(new UserAccount(null, "hash@example.com", encoder.encode(raw),
                AccountStatus.INACTIVA, Set.of(Role.COMPRADOR)));
        String stored = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, saved.id());
        assertThat(stored).isNotEqualTo(raw).startsWith("$2a$12$");
        assertThat(encoder.matches(raw, stored)).isTrue();
        assertThat(encoder.matches("wrong", stored)).isFalse();
        assertThat(accounts.findById(saved.id()).orElseThrow().status()).isEqualTo(AccountStatus.INACTIVA);
        assertThat(saved.toString()).doesNotContain(stored, raw);
    }

    @Test
    void domainAndDatabaseRejectPlaintextPasswords() {
        assertThatThrownBy(() -> new UserAccount(null, "raw@example.com", "plaintext",
                AccountStatus.ACTIVA, Set.of(Role.COMPRADOR))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_accounts(email,password_hash,status) VALUES ('raw@example.com','plaintext','ACTIVA')"))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode()).isEqualTo(3819));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isZero();
    }

    private UserAccount account(String email, Set<Role> roles) {
        return new UserAccount(null, email, encoder.encode("OnlyForThisTest!"), AccountStatus.ACTIVA, roles);
    }
}
