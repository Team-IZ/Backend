package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MemberInvitationRepository {
	Optional<InvitationContext> findActiveOrganization(UUID organizationId);

	Optional<InvitationContext> findInvitableCohort(UUID cohortId);

	boolean existsUserByNormalizedEmail(String normalizedEmail);

	boolean existsIncompleteInvitationByNormalizedEmail(String normalizedEmail);

	boolean existsOrganizationTraineeByNormalizedEmail(UUID organizationId, String normalizedEmail);

	/**
	 * 여러 이메일 중 <b>그 기관에 이미 있는 교육생</b>의 이메일만 한 번에 골라낸다.
	 *
	 * <p>{@link #existsOrganizationTraineeByNormalizedEmail}을 행마다 부르면 900명 명단이 900 왕복이 된다.
	 * Supavisor를 거치는 원격 DB에서는 이 왕복 지연이 그대로 응답 시간에 쌓인다. 판정에 필요한 것은
	 * "이 중 어느 것이 이미 있는가"이므로 한 번에 물어보면 된다.
	 *
	 * @param normalizedEmails 정규화된 이메일. 비어 있으면 질의하지 않는다
	 * @return 이미 존재하는 것만 담긴 집합. 하나도 없으면 빈 집합
	 */
	Set<String> findExistingOrganizationTraineeEmails(UUID organizationId, Collection<String> normalizedEmails);

	/** 초대 대상 기수가 그 기관에 살아 있는지 확인한다. 매니저 초대의 담당 기수 검증용이다. */
	void validateCohort(UUID organizationId, UUID cohortId);

	// ---------------------------------------------------------------------
	// 일괄 등록(대량 CSV) 전용 벌크 연산.
	//
	// 단건 메서드를 행마다 부르면 900명 명단이 약 7,200 왕복이 되어 게이트웨이 응답 한도를 넘는다.
	// 아래 메서드들은 같은 판정·같은 적재를 청크 단위 왕복으로 처리한다. 단건 메서드는 남겨 둔다 —
	// 슈퍼어드민·매니저 초대와 재발송은 건수가 1이라 벌크가 이득이 없고, 충돌이 났을 때
	// 어느 행이 문제인지 가려내는 폴백 경로로도 쓴다.
	// ---------------------------------------------------------------------

	/** {@link #existsIncompleteInvitationByNormalizedEmail}의 일괄판. 진행 중 초대가 있는 이메일만 돌려준다. */
	Set<String> findEmailsWithIncompleteInvitation(Collection<String> normalizedEmails);

	/** {@link #findReusableInvitedUser}의 일괄판. 되살릴 수 있는 자리가 있는 이메일만 담긴다. */
	Map<String, UUID> findReusableInvitedUsers(Collection<String> normalizedEmails);

	/** {@link #existsUserByNormalizedEmail}의 일괄판. 이미 계정이 있는 이메일만 돌려준다. */
	Set<String> findExistingUserEmails(Collection<String> normalizedEmails);

	/** 계정 자리를 배치로 만든다. userId는 호출부가 미리 정한다 — 원장·토큰이 그 값을 참조해야 한다. */
	void createPendingUsers(List<NewPendingUser> users);

	/** 초대 원장을 배치로 만든다. */
	void createInvitations(List<NewInvitation> invitations);

	/** 토큰을 배치로 만든다. */
	void saveTokens(List<InvitationToken> tokens);

	/**
	 * {@link #invalidatePreviousTokens}의 일괄판. 방금 발급한 토큰({@code keepTokenIds})은 남기고
	 * 같은 이메일·목적의 이전 토큰을 REPLACED로 내린다.
	 */
	void invalidatePreviousTokensForEmails(
			UUID organizationId,
			InvitationPurpose purpose,
			Map<String, UUID> newTokenByEmail,
			Instant invalidatedAt
	);

	/** {@link #markInvitationSent}의 일괄판. PENDING인 것만 SENT로 올린다. */
	void markInvitationsSent(List<SentInvitation> invitations, Instant sentAt);

	// ---------------------------------------------------------------------
	// 비동기 등록(개선 D)의 잡 상태. 잡 테이블을 따로 두지 않는다 —
	// batch_request_id가 같은 user_invitation 행들이 곧 잡이고, 잡 상태는 저장하지 않고
	// 집계로 유도한다. 저장하면 행 상태와 잡 상태가 어긋날 수 있는데 유도하면 어긋날 여지가 없다.
	// ---------------------------------------------------------------------

	/**
	 * 일괄 등록 한 건의 진행률을 <b>왕복 한 번으로</b> 집계한다.
	 *
	 * <p>인스턴스가 셋이라 진행률을 인메모리에 두면 폴링이 다른 인스턴스로 갈 때 "그런 잡 없음"이 된다.
	 * 어느 인스턴스가 받아도 같은 DB를 집계하므로 같은 답이 나온다.
	 *
	 * <p>기관·기수로 범위를 좁힌다 — 남의 배치 식별자를 찍어 넣어도 남의 진행률을 볼 수 없어야 한다.
	 *
	 * @return 그 범위에 행이 하나도 없으면 {@link Optional#empty()}
	 */
	Optional<BatchProgress> findBatchProgress(String batchRequestId, UUID organizationId, UUID cohortId);

	/**
	 * 발송이 멈춘 교육생 초대를 <b>클레임하고</b> 가져온다(안전망).
	 *
	 * <p>정상 경로에서는 자리를 확보한 인스턴스가 곧바로 비동기로 발송하므로 여기 걸리는 것이 없다.
	 * 배포·크래시로 그 발송이 끊기면 행이 {@code PENDING}인 채 남는데, {@code mail_claimed_at}이
	 * 갱신되지 않으므로 "오래된 클레임"으로 드러난다. 이 값 자체가 하트비트라 별도 컬럼이 필요 없다.
	 *
	 * <p><b>클레임은 선택이 아니다.</b> 배포본이 셋이고 같은 DB에 붙어 있어, PENDING을 그냥 조회하면
	 * 세 인스턴스가 같은 초대를 집어 교육생에게 메일이 3통 간다. {@code FOR UPDATE SKIP LOCKED}로
	 * 한 인스턴스만 집게 한다.
	 *
	 * @param claimedAt     이번 클레임 시각. 이 값이 다음 주기의 스톨 판정 기준이 된다
	 * @param stalledBefore 이 시각보다 오래된 클레임만 회수 대상이다
	 * @param limit         한 주기에 집을 최대 건수
	 */
	List<StalledInvitation> claimStalledTraineeInvitations(Instant claimedAt, Instant stalledBefore, int limit);

	/**
	 * 일괄 등록 한 건의 집계. 잡 상태는 이 숫자들에서 유도한다 —
	 * {@code mailPendingCount > 0}이면 진행 중, 아니면서 실패가 있으면 부분 성공, 둘 다 없으면 완료다.
	 *
	 * @param invitationSentCount 발송이 끝난 수. 이미 수락(ACCEPTED)한 교육생도 메일을 받은 것이므로 포함한다
	 */
	record BatchProgress(
			int registeredCount,
			int invitationSentCount,
			int mailFailedCount,
			int mailPendingCount
	) {
	}

	/**
	 * 안전망이 이어받을 초대 하나. 메일 본문을 다시 만들 수 있을 만큼 전부 담는다.
	 *
	 * <p><b>토큰 원문은 DB에 없다</b> — {@code one_time_token.token_hash}는 SHA-256이라 저장된 값으로
	 * 초대 링크를 되살릴 수 없다. 그래서 안전망은 재발송과 같은 방식으로 <b>토큰을 새로 발급</b>한다.
	 */
	record StalledInvitation(
			UUID invitationId,
			UUID userId,
			String email,
			String normalizedEmail,
			String name,
			UUID invitedBy,
			String batchRequestId,
			InvitationContext context
	) {
	}

	/** 자리 하나. {@code passwordHash}는 PENDING 자리의 placeholder다. */
	record NewPendingUser(
			UUID userId,
			UUID organizationId,
			String email,
			String normalizedEmail,
			String name,
			Role role,
			String passwordHash,
			Instant now
	) {
	}

	/**
	 * 초대 원장 하나.
	 *
	 * @param batchRequestId 이 행이 속한 일괄 등록의 식별자이며 폴링의 잡 ID다.
	 *                       단건 초대(슈퍼어드민·오퍼레이터·매니저)는 {@code null}이다
	 */
	record NewInvitation(
			UUID invitationId,
			UUID organizationId,
			String email,
			String normalizedEmail,
			Role targetRole,
			UUID targetCohortId,
			UUID invitedBy,
			Instant invitedAt,
			String batchRequestId
	) {
	}

	/** 발송 성공을 기록할 대상. */
	record SentInvitation(UUID invitationId, UUID tokenId) {
	}

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

	/**
	 * @param batchRequestId 일괄 등록의 폴백 경로에서만 값이 있다. 단건 초대는 {@code null}이며,
	 *                       값이 있으면 발송 클레임({@code mail_claimed_at})도 함께 찍는다 —
	 *                       자리를 확보한 인스턴스가 곧바로 발송을 이어받기 때문이다
	 */
	UUID createInvitation(
			UUID organizationId,
			String email,
			String normalizedEmail,
			Role targetRole,
			UUID targetCohortId,
			UUID invitedBy,
			Instant invitedAt,
			String batchRequestId
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
