package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 기관의 오퍼레이터 계정 목록. 목업 SA-02 ② `이 기관의 오퍼레이터 계정` 표에 대응한다.
 * 컬럼: 이름 / 이메일 / 상태 / 초대일 / 최근 로그인 + 행별 액션(정지·재활성·취소).
 */
@Schema(description = "기관 오퍼레이터 계정 목록 (SA-02 ② 오퍼레이터 탭)")
public record OperatorListResponse(
		UUID organizationId,

		@Schema(description = "활성 오퍼레이터 수. 0이면 기관이 아직 시작되지 않은 상태(`오퍼레이터 미배정`)")
		int activeCount,

		List<Operator> content
) {

	@Schema(description = "오퍼레이터 계정 한 줄")
	public record Operator(
			UUID memberId,

			@Schema(description = "이름. 초대만 되고 아직 활성화되지 않으면 비어 있다(화면에서는 `—`).")
			String name,

			String email,

			@Schema(description = """
					계정 상태. ACTIVE(활성) / PENDING(초대됨) / INACTIVE(정지).
					v06에서 LOCKED가 폐지됐다 — 로그인 연속 실패로 인한 일시 차단은 상태가 아니라
					app_user.login_blocked_until 시각으로 표현한다.
					목업의 `메일 발송 실패` 배지는 계정 상태가 아니라 초대 원장(user_invitation.status=DELIVERY_FAILED)에서 온다.""")
			OperatorAccountStatus status,

			@Schema(description = "최초 초대 시각. 초대 이력이 없으면 null")
			Instant invitedAt,

			@Schema(description = "최근 로그인 시각. 한 번도 로그인하지 않았으면 null(화면에서는 `대기 중`)")
			Instant lastLoginAt,

			@Schema(description = """
					대기 중인 초대 토큰 ID. null이 아니면 아직 수락되지 않은 초대이므로 `취소` 액션을 노출한다.
					취소 호출 시 이 값을 경로에 넣는다.""")
			UUID pendingInvitationTokenId,

			@Schema(description = """
					이 계정을 정지할 수 있는지. 마지막 활성 오퍼레이터는 정지할 수 없다 —
					기관에 들어갈 수 있는 사람이 아무도 없어지기 때문(고아 기관 방지).""")
			boolean suspendable
	) {
	}
}
