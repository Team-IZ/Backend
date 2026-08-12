package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.InvitationState;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 유효한 초대 상태를 기본값으로 두고 검사 대상 한 가지만 어긋뜨린다.
 *
 * <p>{@link InvitationState}는 판정 재료를 통째로 담아 컴포넌트가 21개다. 케이스마다 전부 나열하면
 * 그 테스트가 무엇을 검증하는지 읽히지 않고, 컴포넌트가 하나 늘 때 모든 케이스를 손봐야 한다.
 * 해석 테스트와 활성화 테스트가 같은 기본값을 공유해야 판정이 갈리지 않는 것도 확인된다.
 */
final class InvitationStateFixture {
	static final UUID USER_ID = UUID.randomUUID();
	static final String EMAIL = "invitee@example.com";
	static final String NAME = "초대 사용자";
	static final int ROW_VERSION = 2;

	private UUID tokenId = UUID.randomUUID();
	private UUID userId = USER_ID;
	private Role role = Role.TRAINEE;
	private String purpose = InvitationPurpose.INVITE_TRAINEE.name();
	private String invitationStatus = "SENT";
	private String userStatus = "PENDING";
	private String organizationStatus = "ACTIVE";
	private UUID organizationId = UUID.randomUUID();
	private boolean currentToken = true;
	private boolean emailMatched = true;
	private boolean organizationMatched = true;
	private boolean userDeleted;
	private boolean organizationDeleted;
	private boolean onRoster = true;
	private Instant expiresAt = Instant.now().plus(Duration.ofDays(3));
	private Instant usedAt;
	private Instant invalidatedAt;
	private String invalidatedReason;

	static InvitationStateFixture valid() {
		return new InvitationStateFixture();
	}

	/** 운영 계정 초대의 기본형. 명단 항목이 없는 것이 정상이다. */
	static InvitationStateFixture staff(Role role) {
		return valid()
				.withRole(role)
				.withPurpose(InvitationPurpose.INVITE_OPERATOR_MANAGER)
				.withOnRoster(false);
	}

	InvitationStateFixture withTokenId(UUID value) {
		tokenId = value;
		return this;
	}

	InvitationStateFixture withUserId(UUID value) {
		userId = value;
		return this;
	}

	InvitationStateFixture withRole(Role value) {
		role = value;
		return this;
	}

	InvitationStateFixture withPurpose(InvitationPurpose value) {
		purpose = value.name();
		return this;
	}

	InvitationStateFixture withInvitationStatus(String value) {
		invitationStatus = value;
		return this;
	}

	InvitationStateFixture withUserStatus(String value) {
		userStatus = value;
		return this;
	}

	InvitationStateFixture withOrganizationStatus(String value) {
		organizationStatus = value;
		return this;
	}

	/** 슈퍼어드민 초대는 어느 기관에도 속하지 않아 기관 컬럼이 통째로 NULL 이다. */
	InvitationStateFixture withoutOrganization() {
		organizationStatus = null;
		organizationId = null;
		return this;
	}

	InvitationStateFixture withCurrentToken(boolean value) {
		currentToken = value;
		return this;
	}

	InvitationStateFixture withEmailMatched(boolean value) {
		emailMatched = value;
		return this;
	}

	InvitationStateFixture withOnRoster(boolean value) {
		onRoster = value;
		return this;
	}

	InvitationStateFixture withExpiresAt(Instant value) {
		expiresAt = value;
		return this;
	}

	InvitationStateFixture withUsedAt(Instant value) {
		usedAt = value;
		return this;
	}

	InvitationStateFixture withInvalidated(Instant at, String reason) {
		invalidatedAt = at;
		invalidatedReason = reason;
		return this;
	}

	InvitationState build() {
		return new InvitationState(
				tokenId,
				userId,
				organizationId,
				EMAIL,
				NAME,
				role,
				ROW_VERSION,
				purpose,
				invitationStatus,
				userStatus,
				organizationStatus,
				currentToken,
				emailMatched,
				organizationMatched,
				userDeleted,
				organizationDeleted,
				onRoster,
				expiresAt,
				usedAt,
				invalidatedAt,
				invalidatedReason
		);
	}
}
