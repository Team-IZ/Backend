package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 기관의 오퍼레이터 계정 목록. 목업 SA-02 ② `이 기관의 오퍼레이터 계정` 표에 대응한다.
 * 컬럼: 이름 / 이메일 / 상태 / 초대일 / 최근 로그인 + 행별 액션(정지·재활성·취소).
 *
 * <p>항목 이름이 {@code OperatorListItem}인 이유 — 스펙의 스키마 이름은 <b>중첩 record의 홑이름</b>으로
 * 정해진다. 예전에는 이 항목도 {@code Operator}였는데 {@code OrganizationResponse.Operator}(이름·이메일만
 * 담는 요약)와 이름이 같아 <b>한쪽이 다른 쪽을 덮었다.</b> 좁은 쪽이 남는 바람에 목록 응답의 스키마에
 * {@code status}·{@code suspendable} 같은 필드가 통째로 사라졌고, 스펙으로 타입을 생성하는 화면에서는
 * 서버가 내려주는 값을 쓸 방법이 없었다. 두 응답이 같은 이름을 쓰지 않게 갈라 둔다.
 */
@Schema(description = "기관 오퍼레이터 계정 목록 (SA-02 ② 오퍼레이터 탭)")
public record OperatorListResponse(
		UUID organizationId,

		@Schema(description = "활성 오퍼레이터 수. 0이면 기관이 아직 시작되지 않은 상태(`오퍼레이터 미배정`)")
		int activeCount,

		List<OperatorListItem> content
) {

	@Schema(description = "오퍼레이터 계정 한 줄")
	public record OperatorListItem(
			UUID memberId,

			@Schema(description = "이름. 초대만 되고 아직 활성화되지 않으면 비어 있다(화면에서는 `—`).", nullable = true)
			String name,

			String email,

			OperatorAccountStatus status,

			@Schema(description = "최초 초대 시각. 초대 이력이 없으면 null", nullable = true)
			Instant invitedAt,

			@Schema(description = "최근 로그인 시각. 한 번도 로그인하지 않았으면 null(화면에서는 `대기 중`)", nullable = true)
			Instant lastLoginAt,

			@Schema(description = """
					대기 중인 초대 토큰 ID. null이 아니면 아직 수락되지 않은 초대이므로 `취소` 액션을 노출한다.
					취소 호출 시 이 값을 경로에 넣는다.""", nullable = true)
			UUID pendingInvitationTokenId,

			@Schema(description = """
					이 계정을 정지할 수 있는지. 마지막 활성 오퍼레이터는 정지할 수 없다 —
					기관에 들어갈 수 있는 사람이 아무도 없어지기 때문(고아 기관 방지).""")
			boolean suspendable,

			@Schema(description = """
					가장 최근 초대의 메일 발송이 실패했는지(user_invitation.status = DELIVERY_FAILED).
					true면 목업 case 4·5의 `오퍼레이터로 지정됐지만 초대 메일이 나가지 않았습니다` 안내와
					[재발송] 액션을 노출한다.

					status(PENDING)와 구분해서 쓴다 — 둘 다 아직 활성화 전이지만 화면이 할 말이 다르다.
					false + PENDING은 `수락 대기`, true는 `재발송 필요`다.
					실패 후 다시 초대해 성공하면 false로 돌아온다(가장 최근 초대 기준).""")
			boolean invitationDeliveryFailed
	) {
	}
}
