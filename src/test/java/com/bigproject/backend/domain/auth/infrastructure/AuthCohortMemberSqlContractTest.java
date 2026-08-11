package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthCohortMemberJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCohortMemberSqlContractTest {

	@Test
	void createsActiveMembershipFromTheLockedInvitationScope() throws NoSuchMethodException {
		Query query = AuthCohortMemberJpaRepository.class
				.getDeclaredMethod("createActiveFromInvitation", UUID.class, UUID.class, Instant.class)
				.getAnnotation(Query.class);

		assertThat(query.value())
				.contains("INSERT INTO cohort_member")
				.contains("'ACTIVE'")
				.contains("JOIN user_invitation")
				.contains("JOIN cohort")
				.contains("ui.current_token_id = ott.token_id")
				.contains("c.status <> 'CLOSED'")
				.contains("c.deleted_at IS NULL")
				.contains("FOR SHARE OF c")
				.doesNotContain("'INVITED'");
	}

	@Test
	void invitationStateUsesTheInvitationCohortInsteadOfAPrecreatedMembership() {
		assertThat(OneTimeTokenJpaRepository.INVITATION_STATE_SELECT)
				.contains("FROM cohort c")
				.contains("c.cohort_id = ui.target_cohort_id")
				.contains("c.status <> 'CLOSED'")
				.doesNotContain("FROM cohort_member");
	}

	@Test
	void resendLookupDoesNotReturnAnInvitationForAClosedOrDeletedCohort() throws NoSuchMethodException {
		Query query = AuthUserJpaRepository.class
				.getDeclaredMethod("findPasswordResetAccount", String.class)
				.getAnnotation(Query.class);

		assertThat(query.value())
				.contains("invitation_cohort.status <> 'CLOSED'")
				.contains("invitation_cohort.deleted_at IS NULL");
	}
}
