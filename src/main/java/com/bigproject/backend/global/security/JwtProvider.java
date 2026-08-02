package com.bigproject.backend.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {
	private static final String TOKEN_TYPE_CLAIM = "tokenType";
	private static final String ROLE_CLAIM = "role";
	private static final String ORGANIZATION_ID_CLAIM = "organizationId";
	private static final String PASSWORD_CHANGED_AT_CLAIM = "passwordChangedAt";
	private static final String ACCESS_TOKEN_TYPE = "access";
	private static final String REFRESH_TOKEN_TYPE = "refresh";

	private final String secret;
	private final long accessTokenExpiration;
	private final long refreshTokenExpiration;

	public JwtProvider(
			@Value("${jwt.secret:}") String secret,
			@Value("${jwt.access-token-expiration:1800000}") long accessTokenExpiration,
			@Value("${jwt.refresh-token-expiration:604800000}") long refreshTokenExpiration
	) {
		this.secret = secret;
		this.accessTokenExpiration = accessTokenExpiration;
		this.refreshTokenExpiration = refreshTokenExpiration;
	}

	public String createAccessToken(String email, String role, UUID organizationId) {
		return createAccessToken(email, role, organizationId, null);
	}

	public String createAccessToken(String email, String role, UUID organizationId, Instant passwordChangedAt) {
		return createToken(email, role, organizationId, passwordChangedAt, ACCESS_TOKEN_TYPE, accessTokenExpiration);
	}

	public String createRefreshToken(String email, String role, UUID organizationId) {
		return createRefreshToken(email, role, organizationId, null);
	}

	public String createRefreshToken(String email, String role, UUID organizationId, Instant passwordChangedAt) {
		return createToken(email, role, organizationId, passwordChangedAt, REFRESH_TOKEN_TYPE, refreshTokenExpiration);
	}

	public long getAccessTokenExpiration() {
		return accessTokenExpiration;
	}

	public long getRefreshTokenExpiration() {
		return refreshTokenExpiration;
	}

	public boolean isAccessToken(String token) {
		return hasTokenType(token, ACCESS_TOKEN_TYPE);
	}

	public boolean isRefreshToken(String token) {
		return hasTokenType(token, REFRESH_TOKEN_TYPE);
	}

	public String getEmail(String token) {
		return parseClaims(token).getSubject();
	}

	public String getRole(String token) {
		return parseClaims(token).get(ROLE_CLAIM, String.class);
	}

	public UUID getOrganizationId(String token) {
		String organizationId = parseClaims(token).get(ORGANIZATION_ID_CLAIM, String.class);
		return organizationId == null ? null : UUID.fromString(organizationId);
	}

	public Instant getExpiration(String token) {
		return parseClaims(token).getExpiration().toInstant();
	}

	public Instant getIssuedAt(String token) {
		return parseClaims(token).getIssuedAt().toInstant();
	}

	public Instant getPasswordChangedAt(String token) {
		Object value = parseClaims(token).get(PASSWORD_CHANGED_AT_CLAIM);
		return value instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : null;
	}

	public boolean validateToken(String token) {
		try {
			parseClaims(token);
			return true;
		} catch (JwtException | IllegalArgumentException | IllegalStateException exception) {
			return false;
		}
	}

	private String createToken(
			String email,
			String role,
			UUID organizationId,
			Instant passwordChangedAt,
			String tokenType,
			long expiration
	) {
		Instant now = Instant.now();
		return Jwts.builder()
				.id(UUID.randomUUID().toString())
				.subject(email)
				.claim(TOKEN_TYPE_CLAIM, tokenType)
				.claim(ROLE_CLAIM, role)
				.claim(ORGANIZATION_ID_CLAIM, organizationId == null ? null : organizationId.toString())
				.claim(PASSWORD_CHANGED_AT_CLAIM, passwordChangedAt == null ? null : passwordChangedAt.toEpochMilli())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusMillis(expiration)))
				.signWith(secretKey())
				.compact();
	}

	private boolean hasTokenType(String token, String tokenType) {
		return validateToken(token) && tokenType.equals(parseClaims(token).get(TOKEN_TYPE_CLAIM, String.class));
	}

	private Claims parseClaims(String token) {
		return Jwts.parser()
				.verifyWith(secretKey())
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}

	private SecretKey secretKey() {
		if (secret.isBlank()) {
			throw new IllegalStateException("JWT_SECRET 환경 변수가 설정되지 않았습니다.");
		}
		return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}
}
