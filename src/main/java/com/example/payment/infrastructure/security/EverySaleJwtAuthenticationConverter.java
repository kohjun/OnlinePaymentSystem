package com.example.payment.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class EverySaleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopeAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        Collection<GrantedAuthority> scopeAuthorities = scopeAuthoritiesConverter.convert(jwt);
        if (scopeAuthorities != null) {
            authorities.addAll(scopeAuthorities);
        }

        addRoleAuthorities(authorities, jwt.getClaim("roles"));
        addRoleAuthorities(authorities, jwt.getClaim("authorities"));
        addRoleAuthorities(authorities, jwt.getClaim("groups"));
        addRealmAccessAuthorities(authorities, jwt.getClaim("realm_access"));
        addResourceAccessAuthorities(authorities, jwt.getClaim("resource_access"));

        return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    }

    private void addRealmAccessAuthorities(Set<GrantedAuthority> authorities, Object claim) {
        if (claim instanceof Map<?, ?> map) {
            addRoleAuthorities(authorities, map.get("roles"));
        }
    }

    private void addResourceAccessAuthorities(Set<GrantedAuthority> authorities, Object claim) {
        if (!(claim instanceof Map<?, ?> resources)) {
            return;
        }
        for (Object resource : resources.values()) {
            if (resource instanceof Map<?, ?> map) {
                addRoleAuthorities(authorities, map.get("roles"));
            }
        }
    }

    private void addRoleAuthorities(Set<GrantedAuthority> authorities, Object value) {
        for (String role : values(value)) {
            if (role.isBlank()) {
                continue;
            }
            String normalized = role.trim();
            if (normalized.startsWith("ROLE_") || normalized.startsWith("SCOPE_")) {
                authorities.add(new SimpleGrantedAuthority(normalized));
            } else {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + normalized.toUpperCase()));
            }
        }
    }

    private Set<String> values(Object value) {
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof String string) {
            for (String token : string.split("[ ,]")) {
                if (!token.isBlank()) {
                    values.add(token.trim());
                }
            }
            return values;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    values.add(item.toString());
                }
            }
        }
        return values;
    }

    private String principalName(Jwt jwt) {
        String customerId = firstText(jwt, "customerId", "customer_id", "cid");
        if (customerId != null) {
            return customerId;
        }
        String preferredUsername = firstText(jwt, "preferred_username", "email");
        if (preferredUsername != null) {
            return preferredUsername;
        }
        return jwt.getSubject();
    }

    private String firstText(Jwt jwt, String... claimNames) {
        for (String claimName : claimNames) {
            Object value = jwt.getClaims().get(claimName);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }
}