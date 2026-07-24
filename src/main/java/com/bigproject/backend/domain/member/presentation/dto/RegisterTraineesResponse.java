package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "CSV 교육생 등록·초대 처리 결과")
public record RegisterTraineesResponse(
		@Schema(description = "CSV에서 처리 대상으로 읽은 전체 교육생 행 수", example = "3")
		int requestedCount,
		@Schema(description = "계정과 기수 소속 등록에 성공한 교육생 수", example = "2")
		int registeredCount,
		@Schema(description = "SMTP 서버에 초대 메일 접수가 완료된 교육생 수", example = "2")
		int invitationSentCount,
		@Schema(description = "수정 또는 재처리가 필요한 CSV 행별 실패 목록")
		List<Failure> failures
) {
	@Schema(description = "CSV 행별 실패 정보")
	public record Failure(
			@Schema(description = "헤더 행을 포함한 CSV의 실제 행 번호", example = "4")
			int row,
			@Schema(description = "해당 CSV 행에 입력된 이메일", example = "invalid-email")
			String email,
			@Schema(
					description = "실패 상태: 1=유효하지 않은 이메일 형식, 2=CSV 내부 중복 이메일, "
							+ "3=기관에 이미 존재하는 교육생 역할 이메일 계정",
					example = "1",
					allowableValues = {"1", "2", "3"}
			)
			int status
	) {
	}
}
