package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.infrastructure.jpa.AuditLogJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthCohortMemberJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaEntity;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.ConsentRecordJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.LoginCohortJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.RefreshTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.UserInvitationJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import jakarta.persistence.Column;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:auth-jpa-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
class AuthJpaRepositoryContextTest {
	@Autowired
	private AuthUserJpaRepository userRepository;
	@Autowired
	private OneTimeTokenJpaRepository tokenRepository;
	@Autowired
	private UserInvitationJpaRepository invitationRepository;
	@Autowired
	private RefreshTokenJpaRepository refreshTokenRepository;
	@Autowired
	private ConsentRecordJpaRepository consentRepository;
	@Autowired
	private AuditLogJpaRepository auditLogRepository;
	@Autowired
	private LoginCohortJpaRepository loginCohortRepository;
	@Autowired
	private AuthCohortMemberJpaRepository cohortMemberRepository;

	@Test
	void createsAllAuthJpaRepositoryProxies() {
		assertThat(userRepository).isNotNull();
		assertThat(tokenRepository).isNotNull();
		assertThat(invitationRepository).isNotNull();
		assertThat(refreshTokenRepository).isNotNull();
		assertThat(consentRepository).isNotNull();
		assertThat(auditLogRepository).isNotNull();
		assertThat(loginCohortRepository).isNotNull();
		assertThat(cohortMemberRepository).isNotNull();
	}

	@Test
	void mapsEmailAsPostgresqlCitext() throws NoSuchFieldException {
		Field email = AuthUserJpaEntity.class.getDeclaredField("email");

		assertThat(email.getAnnotation(Column.class).columnDefinition()).isEqualTo("citext");
	}
}
