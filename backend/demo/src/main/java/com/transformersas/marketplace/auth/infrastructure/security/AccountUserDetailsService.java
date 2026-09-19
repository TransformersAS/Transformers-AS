package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.users.application.usecase.FindAccountByEmail;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

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
        return new AccountPrincipal(account);
    }
}
