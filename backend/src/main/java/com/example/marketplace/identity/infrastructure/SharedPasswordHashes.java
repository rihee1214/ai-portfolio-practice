package com.example.marketplace.identity.infrastructure;
import com.example.marketplace.identity.api.PasswordHashes;
/** Public module adapter; the existing member PasswordHasher bean remains unambiguous. */
public final class SharedPasswordHashes implements PasswordHashes {
    private final ArgonPasswordHasher delegate=new ArgonPasswordHasher();
    @Override public String encode(String password) { return delegate.encode(password); }
    @Override public boolean matches(String password,String hash) { return delegate.matches(password,hash); }
}
