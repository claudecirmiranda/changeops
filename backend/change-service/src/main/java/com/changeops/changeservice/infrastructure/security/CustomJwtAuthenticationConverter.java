package com.changeops.changeservice.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CustomJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractRoles(jwt);
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private Collection<GrantedAuthority> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        }
        Object rolesObj = realmAccess.get("roles");
        if (!(rolesObj instanceof List<?> rawList)) {
            return List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        }
        return rawList.stream()
                .filter(String.class::isInstance)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + ((String) r).toUpperCase()))
                .collect(Collectors.toList());
    }
}
