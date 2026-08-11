package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;

import java.time.Instant;

/**
 * 초대 토큰의 상태를 화면이 갈라 쓸 수 있는 에러 코드 하나로 판정한다.
 *
 * <p>초대 해석({@code /auth/invitations/resolve})과 활성화({@code /auth/manager-signup},
 * {@code /auth/trainee-activation})가 <b>같은 판정을 공유한다</b>. 링크를 열 때는 "만료"라고 했다가
 * 제출할 때는 "유효하지 않음"이라고 하면 같은 링크에 두 가지 안내가 붙는다.
 *
 * <h2>판정 순서가 곧 계약이다</h2>
 * 한 토큰이 여러 조건에 동시에 걸리므로 <b>먼저 보는 것이 답을 정한다</b>. 순서는 다음과 같다.
 * <ol>
 *   <li><b>구조적 무효</b> — 계정 삭제, 토큰과 계정의 이메일·기관 불일치, 기관 정지·삭제.
 *       사용자가 할 수 있는 일이 없으므로 재발송도 로그인도 안내하지 않는다.</li>
 *   <li><b>이미 가입</b> — 만료보다 <b>먼저</b> 본다. 수락된 초대는 시간이 지나면 만료 조건에도
 *       걸리는데, 그때 맞는 안내는 재발송이 아니라 로그인이기 때문이다.</li>
 *   <li><b>만료</b> — 기한 경과, 초대 만료, 그리고 <b>재발송으로 교체된 토큰</b>. 교체는 더 새 메일이
 *       있다는 뜻이라 "문의"보다 "재발송/새 메일 확인" 안내가 맞다.</li>
 *   <li><b>명단 외</b> — 교육생만 해당. 초대가 취소됐거나 {@code cohort_member}에 살아 있는 행이 없다.</li>
 *   <li>그 밖의 모든 어긋남 — 무효.</li>
 * </ol>
 */
public final class InvitationStateClassifier {
	/**
	 * 무효 판정은 <b>어느 조건에서 걸렸는지 알려 주지 않는다</b>. 위변조·다른 기관 토큰 같은 경우라
	 * 세부를 흘리면 토큰을 긁어 보는 쪽에 단서가 된다. 만료·이미가입·명단외는 반대로 사용자가
	 * 다음 행동을 골라야 하므로 갈라 준다.
	 */
	private static final String INVALID_MESSAGE = "유효하지 않거나 만료된 초대입니다.";

	private InvitationStateClassifier() {
	}

	/** 판정 통과 시 조용히 반환하고, 걸리면 화면이 분기할 코드를 실은 {@link ApiException}을 던진다. */
	public static void verify(InvitationState state, Instant now) {
		if (state.userDeleted()
				|| !state.emailMatched()
				|| !state.organizationMatched()
				|| isOrganizationUnusable(state)) {
			throw invalid();
		}

		if (state.usedAt() != null
				|| "ACCEPTED".equals(state.invitationStatus())
				|| "ACTIVE".equals(state.userStatus())) {
			throw new ApiException(AuthErrorCode.INVITATION_ALREADY_ACCEPTED);
		}

		if (!state.expiresAt().isAfter(now)
				|| "EXPIRED".equals(state.invitationStatus())
				|| "REPLACED".equals(state.invalidatedReason())
				|| !state.currentToken()) {
			throw new ApiException(AuthErrorCode.INVITATION_EXPIRED);
		}

		if (state.role() == Role.TRAINEE
				&& ("CANCELLED".equals(state.invitationStatus()) || !state.onRoster())) {
			throw new ApiException(AuthErrorCode.INVITATION_NOT_IN_ROSTER);
		}

		if (!"SENT".equals(state.invitationStatus())
				|| state.invalidatedAt() != null
				|| !"PENDING".equals(state.userStatus())) {
			throw invalid();
		}
	}

	public static ApiException invalid() {
		return new ApiException(AuthErrorCode.INVITATION_INVALID, INVALID_MESSAGE);
	}

	/**
	 * 기관 검사는 <b>기관이 있을 때만</b> 한다. 슈퍼어드민 초대는 어느 기관에도 속하지 않아
	 * {@code org_id}가 NULL 이고, 그 경우 상태 컬럼도 통째로 NULL 로 올라온다.
	 */
	private static boolean isOrganizationUnusable(InvitationState state) {
		if (state.organizationStatus() == null) {
			return false;
		}
		return !"ACTIVE".equals(state.organizationStatus()) || state.organizationDeleted();
	}
}
