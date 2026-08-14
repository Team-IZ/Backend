package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.application.TraineeRegistrationProgress;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "교육생 일괄 등록 1건의 초대 메일 발송 진행률")
public record TraineeRegistrationProgressResponse(
		@Schema(description = "진행률 조회에 쓴 일괄 등록 식별자", example = "trainee-batch-001")
		String batchRequestId,
		@Schema(description = "이 일괄 등록으로 만들어진 초대 원장 수. 등록 응답의 registeredCount와 같습니다.", example = "900")
		int registeredCount,
		@Schema(description = "초대 메일 발송이 끝난 수. 이미 가입을 마친 교육생도 메일을 받았으므로 포함합니다.", example = "800")
		int invitationSentCount,
		@Schema(description = "메일이 나가지 못한 수. 명단 화면의 [초대 재발송]으로 복구합니다.", example = "0")
		int mailFailedCount,
		@Schema(description = "아직 발송을 기다리는 수. 0이 되면 발송이 끝난 것입니다.", example = "100")
		int mailPendingCount,
		@Schema(
				description = "RUNNING=발송 중(계속 폴링) · PARTIAL=발송이 끝났고 실패가 있음 · SUCCEEDED=전부 발송됨",
				example = "RUNNING",
				allowableValues = {"RUNNING", "PARTIAL", "SUCCEEDED"}
		)
		TraineeRegistrationProgress.Status status
) {
	public static TraineeRegistrationProgressResponse of(
			String batchRequestId,
			TraineeRegistrationProgress progress
	) {
		return new TraineeRegistrationProgressResponse(
				batchRequestId,
				progress.registeredCount(),
				progress.invitationSentCount(),
				progress.mailFailedCount(),
				progress.mailPendingCount(),
				progress.status()
		);
	}
}
