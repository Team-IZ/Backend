package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MemberInvitationRepository {
	Optional<InvitationContext> findActiveOrganization(UUID organizationId);

	Optional<InvitationContext> findInvitableCohort(UUID cohortId);

	boolean existsUserByNormalizedEmail(String normalizedEmail);

	boolean existsIncompleteInvitationByNormalizedEmail(String normalizedEmail);

	boolean existsOrganizationTraineeByNormalizedEmail(UUID organizationId, String normalizedEmail);

	/** 초대 대상 기수가 그 기관에 살아 있는지 확인한다. 매니저 초대의 담당 기수 검증용이다. */
	void validateCohort(UUID organizationId, UUID cohortId);

	/**
	 * 초대만 받고 <b>한 번도 활성화된 적 없는</b> 계정 자리를 찾는다. 초대가 취소되면 계정은 지워지지 않고
	 * INACTIVE로 남는데(이력 보존), 그 자리가 이메일을 계속 점유해 같은 주소로 재초대하면 409가 났다.
	 * 재초대는 새 자리를 만들지 않고 이 자리를 되살린다.
	 *
	 * <p>판별 기준은 {@code is_email_verified = FALSE}다. 계정 자리를 만들 때 FALSE로 넣고
	 * 활성화(AU-02)가 TRUE로 바꾸므로, FALSE면 확실히 <b>한 번도 활성화된 적 없는</b> 자리다.
	 * 실제로 쓰던 계정을 재초대로 덮어쓰는 사고를 막는다.
	 * ({@code password_changed_at}은 자리를 만들 때 이미 채워져 판별에 쓸 수 없다.)
	 */
	Optional<UUID> findReusableInvitedUser(String normalizedEmail);

	/** 취소돼 INACTIVE로 내려간 계정 자리를 다시 PENDING으로 되돌린다(재초대). 소속·역할도 이번 초대 기준으로 맞춘다. */
	void reactivateInvitedUser(UUID userId, UUID organizationId, String email, String normalizedEmail, String name, Role role, String passwordHash, Instant now);

	UUID createPendingUser(UUID organizationId, String email, String normalizedEmail, String name, Role role, String passwordHash, Instant now);

	UUID createInvitation(
			UUID organizationId,
			String email,
			String normalizedEmail,
			Role targetRole,
			UUID targetCohortId,
			UUID invitedBy,
			Instant invitedAt
	);

	/**
	 * 재발송 대상 초대를 토큰으로 찾는다.
	 *
	 * <p><b>만료된 토큰도 대상이다</b> — 만료야말로 재발송이 필요한 상황이다. 이미 수락됐거나(ACCEPTED)
	 * 취소된(CANCELLED) 초대만 제외한다.
	 */
	Optional<ResendableInvitation> findResendableInvitation(UUID tokenId);

	/**
	 * 재발송 성공을 기록한다. 상태를 SENT로 되돌리고 실패 정보를 지우며 재발송 횟수를 올린다.
	 * DELIVERY_FAILED에서 올라오는 경로가 있으므로 실패 컬럼 4개를 함께 비운다.
	 */
	void markInvitationResent(UUID invitationId, UUID tokenId, Instant sentAt);

	void saveToken(InvitationToken token);

	void markInvitationSent(UUID invitationId, UUID tokenId, Instant sentAt);

	/**
	 * 초대 메일 발송이 실패했음을 원장에 기록한다(목업 case 4·5 `초대 메일이 나가지 않았습니다` + [재발송]).
	 *
	 * <p>계정 자리는 지우지 않는다 — 지우면 같은 주소로 다시 초대했을 때 중복 초대인지 재시도인지
	 * 구분할 수 없어진다.
	 *
	 * <p>{@code ck_user_invitation_updated_at_5}가 status·failure_stage·failure_code·failed_at을
	 * <b>한 세트로</b> 요구하므로 넷을 동시에 쓴다.
	 */
	void markInvitationDeliveryFailed(UUID invitationId, UUID tokenId, String failureReason, Instant failedAt);

	void invalidatePreviousTokens(InvitationToken replacement, Instant invalidatedAt);

	/**
	 * 재발송에 필요한 초대 한 건.
	 *
	 * @param purpose        어떤 메일을 다시 보낼지 결정한다(슈퍼어드민·오퍼레이터/매니저·교육생).
	 * @param targetRole     누가 재발송할 수 있는지 판정한다. 초대 때와 같은 권한 규칙을 쓴다.
	 * @param organizationId 슈퍼어드민 초대는 null이다.
	 * @param name           교육생 초대 메일이 이름을 쓰므로 함께 가져온다.
	 */
	record ResendableInvitation(
			UUID invitationId,
			UUID userId,
			String email,
			String normalizedEmail,
			String name,
			Role targetRole,
			InvitationPurpose purpose,
			UUID organizationId,
			String organizationName,
			UUID cohortId,
			String cohortName
	) {
		public InvitationContext context() {
			return new InvitationContext(organizationId, organizationName, cohortId, cohortName);
		}
	}
}
