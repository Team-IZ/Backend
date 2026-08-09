package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = """
		오퍼레이터·매니저 초대 발송 결과. 초대 메일 발송 성공은 계정 활성화 완료를 의미하지 않습니다.

		`role`은 초대 경로별로 고정됩니다 — 매니저 초대는 MANAGER, 오퍼레이터 초대는 OPERATOR입니다.
		`status`는 발송 직후라 항상 INVITED입니다.""")
public record InviteManagerResponse(
		@Schema(description = "초대 대상 계정 식별자", type = "string", example = "UUID") UUID memberId,
		@Schema(description = "초대 메일 수신 이메일", example = "manager@example.com") String email,
		Role role,
		AccountStatus status,
		@Schema(description = "초대 원장과 최초 토큰을 생성한 시각", example = "2026-08-02T12:00:00Z") Instant invitedAt
) {
}
