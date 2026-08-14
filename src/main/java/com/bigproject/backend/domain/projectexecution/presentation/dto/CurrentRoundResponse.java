package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.CurrentRoundStatus;
import com.bigproject.backend.domain.projectexecution.domain.DefaultActionCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		교육생 홈(TR-01)의 "지금 할 일 하나". trainee_home_round_view 한 행을 그대로 옮긴 것이다.

		status·defaultActionCode 둘 다 뷰가 계산해서 준다 — 화면은 status로 문구를,
		defaultActionCode로 버튼을 결정하면 된다. 나머지 필드는 상태별로 의미 있는 것만 채워지고
		해당 없으면 null이다.
		""")
public record CurrentRoundResponse(
        @Schema(description = "지금 상태 하나. NO_ACTIVE_ROUND면 이하 필드가 전부 null이다.")
        CurrentRoundStatus status,
        @Schema(description = "지금 보여줄 기본 액션 버튼. NO_ACTIVE_ROUND면 null.", nullable = true)
        DefaultActionCode defaultActionCode,
        @Schema(description = """
				추가 경고 배지 목록(복수 가능). 예: 제출 마감 지남과 별개로 분석도 실패했을 때
				둘 다 담긴다. status/defaultActionCode와 겹칠 수 있다.
				""")
        List<String> warningCodes,
        @Schema(description = "defaultActionCode가 왜 지금 비활성인지. 활성 상태면 null.", nullable = true)
        String actionUnavailableReasonCode,

        @Schema(nullable = true)
        UUID projectId,
        @Schema(nullable = true)
        String projectName,
        @Schema(nullable = true)
        UUID assessmentRoundId,
        @Schema(example = "3", nullable = true)
        Integer roundNo,
        @Schema(nullable = true)
        String roundName,
        @Schema(description = "이 프로젝트에 연결된 교안 이름 목록", nullable = true)
        List<String> curriculumDisplayNames,

        @Schema(description = "제출 마감 시각", nullable = true)
        Instant submissionDueAt,
        @Schema(description = "지금 다시 제출할 수 있는지", nullable = true)
        Boolean canResubmit,

        @Schema(description = "ANALYSIS_FAILED일 때만 값이 있다.", nullable = true)
        String analysisFailureCode,

        @Schema(description = "응시 창이 열리는 시각", nullable = true)
        Instant assessmentOpenAt,
        @Schema(description = "응시 창이 닫히는 시각", nullable = true)
        Instant assessmentCloseAt,

        @Schema(description = "다시 보기 시도 ID. REVIEW_REQUIRED가 아니면 null.", nullable = true)
        UUID reviewAttemptId,
        @Schema(description = "다시 보기 마감 시각", nullable = true)
        Instant reviewDueAt,

        @Schema(description = "NOT_PUBLISHED · GENERATING · PUBLISHED", nullable = true)
        String reportPublishStatus,

        @Schema(description = "담당 매니저 이름. 배정 안 됐으면 null.", nullable = true)
        String managerName,
        @Schema(description = "커밋 이메일 미등록이면 null", nullable = true)
        String commitEmailStatus
) {

    public static CurrentRoundResponse noActiveRound() {
        return new CurrentRoundResponse(
                CurrentRoundStatus.NO_ACTIVE_ROUND, null, List.of(), null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null
        );
    }
}