package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 슈퍼어드민 초대 결과. 받는 사람은 초대 메일 링크에서 이름·비밀번호만 정하면 활성화된다.
 *
 * <p>기관 필드가 없다 — 슈퍼어드민은 어느 기관에도 속하지 않는다({@code app_user.org_id IS NULL}).
 */
@Schema(description = "슈퍼어드민 초대 결과 (SA-03 ② 계정 초대). `status`는 수락 전까지 PENDING(목업 `초대됨`)이다.")
public record InviteSuperAdminResponse(

		@Schema(description = "생성된 계정 자리 식별자")
		UUID memberId,

		@Schema(description = "초대 메일 수신 이메일")
		String email,

		OperatorAccountStatus status,

		@Schema(description = "초대 원장과 최초 토큰을 생성한 시각")
		Instant invitedAt,

		@Schema(description = """
				초대 반영 후의 슈퍼어드민 계정 목록 전체. deactivatable 플래그가 함께 갱신되므로 \
				표를 그대로 다시 그릴 수 있다.""")
		SuperAdminListResponse accounts
) {
}
