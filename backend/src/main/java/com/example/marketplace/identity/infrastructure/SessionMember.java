package com.example.marketplace.identity.infrastructure;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Only ID/version/roles survive credential erasure before JDBC serialization. */
public final class SessionMember implements UserDetails, CredentialsContainer {
    private static final long serialVersionUID = 1L;
    private final String id;
    private final long authVersion;
    private final List<String> roles;
    private String password;
    private final boolean active;

    public SessionMember(String id, long authVersion, List<String> roles, String password, boolean active) {
        this.id = id;
        this.authVersion = authVersion;
        this.roles = List.copyOf(roles);
        this.password = password;
        this.active = active;
    }

    public long authVersion() { return authVersion; }
    public List<String> roles() { return roles; }
    @Override
    public String getUsername() { return id; }
    @Override
    public String getPassword() { return password; }
    @Override
    public boolean isEnabled() { return active; }
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
    @Override
    public void eraseCredentials() { password = null; }
}
