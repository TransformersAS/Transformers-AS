package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.users.application.usecase.FindAccountByEmail;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final FindAccountByEmail findAccount;

    public AccountUserDetailsService(FindAccountByEmail findAccount) {
        this.findAccount = findAccount;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        var account = findAccount.execute(email)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
        return new User(account.email(), account.passwordHash(), account.status() == AccountStatus.ACTIVA,
                true, true, true, List.of());
    }
}
