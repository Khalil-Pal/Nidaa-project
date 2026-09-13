package com.humanitarian.platform.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Spring Security principal plus the instant before which the account's
 * access tokens are no longer honoured (users.tokens_valid_from, S-7).
 */
public class NidaaUserDetails extends User {

    private final LocalDateTime tokensValidFrom;

    public NidaaUserDetails(String email,
                            String passwordHash,
                            boolean enabled,
                            boolean accountNonLocked,
                            Collection<? extends GrantedAuthority> authorities,
                            LocalDateTime tokensValidFrom) {
        super(email, passwordHash, enabled, true, true, accountNonLocked, authorities);
        this.tokensValidFrom = tokensValidFrom;
    }

    /** May be null: no password change has happened since the column was introduced. */
    public LocalDateTime getTokensValidFrom() {
        return tokensValidFrom;
    }
}
