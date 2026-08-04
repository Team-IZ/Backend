package com.bigproject.backend.domain.assessment.domain;

import java.util.UUID;

// 미니프로젝트 평가 회차와 소속 프로젝트의 운영 순서를 함께 담는 조회 모델.
// 화면 표시용 분석 순서(미프 N차)는 projectSequenceNo가 아니라 ProjectRepository에서 별도로 재번호화해 계산한다.
public record MiniProjectRoundView(
		UUID assessmentRoundId,
		UUID projectId,
		UUID cohortId,
		UUID conceptSetId,
		int roundNo,
		String roundName,
		int projectSequenceNo
) {
}
