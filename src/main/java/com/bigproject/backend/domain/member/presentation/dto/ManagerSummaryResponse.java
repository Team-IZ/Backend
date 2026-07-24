package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ManagerSummaryResponse(
		@Schema(description = "매니저 회원 ID", example = "123e4567-e89b-12d3-a456-426614174000")
		UUID memberId,
		@Schema(description = "매니저 이름", example = "홍길동")
		String name,
		@Schema(description = "매니저 이메일", example = "manager@example.com")
		String email,
		@Schema(description = "매니저 권한 표시명", allowableValues = {"총괄", "담당"}, example = "담당")
		String role,
		@Schema(description = "담당 기수명 목록이며 총괄 매니저는 기관 전체를 반환합니다.", example = "[\"7기\", \"8기\"]")
		List<String> cohortNames,
		@Schema(description = "계정 상태 표시명", allowableValues = {"활성화", "초대됨", "비활성화"}, example = "활성화")
		String status,
		@Schema(description = "한국 시간 기준 최근 로그인 날짜이며 로그인 이력이 없으면 null입니다.", example = "2026-07-24")
		LocalDate lastLoginDate
) {
	public ManagerSummaryResponse {
		cohortNames = List.copyOf(cohortNames);
	}
}
