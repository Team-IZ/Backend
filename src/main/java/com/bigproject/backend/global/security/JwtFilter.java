package com.bigproject.backend.global.security;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {
	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ROLE_PREFIX = "ROLE_";

	private final JwtProvider jwtProvider;
	private final AuthUserRepository authUserRepository;

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		String token = resolveToken(request);

		if (token != null && jwtProvider.isAccessToken(token)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			String email = jwtProvider.getEmail(token);
			Optional<AuthUser> candidate = authUserRepository.findByNormalizedEmail(
					email.trim().toLowerCase(Locale.ROOT)
			);
			if (candidate.isPresent() && isCurrentCredential(token, candidate.get())) {
				String role = jwtProvider.getRole(token);
				List<SimpleGrantedAuthority> authorities = role == null
						? List.of()
						: List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role));
				UsernamePasswordAuthenticationToken authentication =
						new UsernamePasswordAuthenticationToken(email, null, authorities);
				authentication.setDetails(jwtProvider.getOrganizationId(token));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}

		filterChain.doFilter(request, response);
	}

	private boolean isCurrentCredential(String token, AuthUser user) {
		if (!"ACTIVE".equals(user.status()) || !user.emailVerified()) {
			return false;
		}
		if (!user.role().name().equals(jwtProvider.getRole(token))
				|| !Objects.equals(user.organizationId(), jwtProvider.getOrganizationId(token))) {
			return false;
		}
		if (user.passwordChangedAt() == null) {
			return true;
		}
		var tokenCredentialVersion = jwtProvider.getPasswordChangedAt(token);
		if (tokenCredentialVersion != null) {
			return tokenCredentialVersion.toEpochMilli() == user.passwordChangedAt().toEpochMilli();
		}
		return !jwtProvider.getIssuedAt(token).isBefore(user.passwordChangedAt().truncatedTo(ChronoUnit.SECONDS));
	}

	private String resolveToken(HttpServletRequest request) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			return null;
		}
		String token = authorization.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}
}
