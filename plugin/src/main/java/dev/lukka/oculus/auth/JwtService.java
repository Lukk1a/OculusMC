package dev.lukka.oculus.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class JwtService {

    private final SecretKey key;
    private final Cache<String, Boolean> denylist;
    private final long accessTtlSeconds;

    public JwtService(File keyFile, long accessTtlSeconds) {
        this.accessTtlSeconds = accessTtlSeconds > 0 ? accessTtlSeconds : 900;
        this.key = loadOrGenerateKey(keyFile);
        this.denylist = Caffeine.newBuilder()
                .expireAfterWrite(this.accessTtlSeconds, TimeUnit.SECONDS)
                .build();
    }

    public JwtService(byte[] rawKey, long accessTtlSeconds) {
        this.accessTtlSeconds = accessTtlSeconds > 0 ? accessTtlSeconds : 900;
        if (rawKey.length < 32) {
            throw new IllegalArgumentException("Key must be at least 256 bits (32 bytes)");
        }
        this.key = Keys.hmacShaKeyFor(rawKey);
        this.denylist = Caffeine.newBuilder()
                .expireAfterWrite(this.accessTtlSeconds, TimeUnit.SECONDS)
                .build();
    }

    private static SecretKey loadOrGenerateKey(File keyFile) {
        if (keyFile != null && keyFile.exists() && keyFile.length() >= 32) {
            try {
                byte[] raw = Files.readAllBytes(keyFile.toPath());
                if (raw.length >= 32) {
                    return Keys.hmacShaKeyFor(raw);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read JWT key file: " + keyFile.getAbsolutePath(), e);
            }
        }

        byte[] generated = new byte[32]; // 256-bit
        new SecureRandom().nextBytes(generated);
        if (keyFile != null) {
            try {
                File parent = keyFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.write(keyFile.toPath(), generated);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write JWT key file: " + keyFile.getAbsolutePath(), e);
            }
        }
        return Keys.hmacShaKeyFor(generated);
    }

    public String generateAccessToken(String username, String role, Collection<String> nodes) {
        String jti = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .id(jti)
                .claim("role", role != null ? role : "Viewer")
                .claim("nodes", nodes != null ? new ArrayList<>(nodes) : List.of())
                .issuedAt(new Date(now))
                .expiration(new Date(now + (accessTtlSeconds * 1000L)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Claims verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getId() != null && denylist.getIfPresent(claims.getId()) != null) {
                return null; // Token is denylisted
            }
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    public Principal parsePrincipal(String token) {
        Claims claims = verifyToken(token);
        if (claims == null) return null;
        String username = claims.getSubject();
        String role = claims.get("role", String.class);
        List<?> rawNodes = claims.get("nodes", List.class);
        Set<String> nodes = new HashSet<>();
        if (rawNodes != null) {
            for (Object obj : rawNodes) {
                if (obj != null) nodes.add(obj.toString());
            }
        }
        return new Principal(username, role, nodes);
    }

    public void revokeToken(String jti) {
        if (jti != null) {
            denylist.put(jti, true);
        }
    }
}
