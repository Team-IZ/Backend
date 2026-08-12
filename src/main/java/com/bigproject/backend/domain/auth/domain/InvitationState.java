package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;

import java.time.Instant;
import java.util.UUID;

/**
 * 초대 토큰 하나의 <b>현재 상태 전부</b>. 판정은 하지 않고 사실만 담는다.
 *
 * <p><b>왜 이런 모양인가.</b> 전에는 조회 SQL이 만료·사용됨·교체됨·초대취소·계정상태·기관정지를
 * 모두 {@code WHERE}에 넣고 {@code Optional}을 돌려줬다. 조건 하나만 어긋나도 결과가 빈 값이라
 * <b>어디서 떨어졌는지가 서비스에 도달하지 못했고</b>, 그래서 네 가지 상황(만료·무효·이미가입·명단외)이
 * 전부 {@code INVITATION_INVALID} 하나로 접혔다. 화면은 "만료됐으니 재발송" 과 "이미 가입됐으니 로그인"을
 * 갈라 안내해야 하는데 그럴 근거가 없었다.
 *
 * <p>그래서 조회는 {@code token_hash} 하나만 걸고 판정 재료를 통째로 올린다. 가르는 일은
 * {@link InvitationStateClassifier}가 한다 — 판정 순서가 곧 프론트와의 계약이라
 * SQL 안에 흩어 두면 순서를 읽을 수도 바꿀 수도 없다.
 *
 * <p><b>계정 열거로 이어지지 않는다.</b> 이 분기는 추측할 수 없는 토큰을 이미 가진 사람에게만 보인다.
 * 로그인·비밀번호 재설정에서 응답을 하나로 합쳤던 이유(이메일만으로 존재 여부를 떠볼 수 있음)가
 * 여기에는 적용되지 않는다.
 *
 * @param role               초대 <b>대상자</b>의 역할. {@code app_user}의 실제 역할이다.
 * @param purpose            {@code one_time_token.purpose} 원문. enum으로 변환하지 않는다 —
 *                           DB에 새 목적이 생겼을 때 {@code valueOf}가 던지면 판정 자체가 불가능해진다.
 * @param organizationStatus 기관이 없으면(슈퍼어드민 초대) {@code null}이다.
 * @param onRoster           교육생 초대가 삭제·종료되지 않은 유효한 기수를 가리키는가. 실제
 *                           {@code cohort_member} 행은 수락할 때 생성한다.
 */
public record InvitationState(
		UUID tokenId,
		UUID userId,
		UUID organizationId,
		String email,
		String name,
		Role role,
		int rowVersion,
		String purpose,
		String invitationStatus,
		String userStatus,
		String organizationStatus,
		boolean currentToken,
		boolean emailMatched,
		boolean organizationMatched,
		boolean userDeleted,
		boolean organizationDeleted,
		boolean onRoster,
		Instant expiresAt,
		Instant usedAt,
		Instant invalidatedAt,
		String invalidatedReason
) {
	public AccountActivationTarget toActivationTarget() {
		return new AccountActivationTarget(
				tokenId,
				userId,
				organizationId,
				email,
				name,
				role,
				rowVersion
		);
	}
}
