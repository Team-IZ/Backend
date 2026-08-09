package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** 오퍼레이터 초대 결과. 받는 사람은 초대 메일 링크로 AU-02에서 이름·비밀번호만 정하면 활성화된다. */
@Schema(description = "오퍼레이터 초대 결과. `status`는 수락 전까지 PENDING(목업 `초대됨`)이다.")
public record InviteOperatorResponse(
		UUID organizationId,
		UUID memberId,
		String email,

		OperatorAccountStatus status,

		Instant invitedAt
) {
}
