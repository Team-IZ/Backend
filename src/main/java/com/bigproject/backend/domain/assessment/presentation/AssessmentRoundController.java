package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.application.AssessmentRoundQueryService;
import com.bigproject.backend.domain.assessment.presentation.dto.AssessmentRoundsResponse;
import com.bigproject.backend.global.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Assessment", description = "교육생 이해도 확인 회차")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/assessment-rounds")
@PreAuthorize("hasRole('TRAINEE')")
@RequiredArgsConstructor
public class AssessmentRoundController {

	private final AssessmentRoundQueryService assessmentRoundQueryService;

	@Operation(
			summary = "교육생 홈 3구획 조회",
			description = """
					**상태**: ✅ 사용 가능

					교육생 홈이 한 화면에 세 구획(지금 할 일 · 예정 · 지난 회차)을 그리므로 요청 하나로 묶습니다.
					원천은 `trainee_home_round_view` 단일 조회이며 스코프는 경로가 아니라 액세스 토큰에서 도출합니다.

					**요청**: 파라미터 없음

					**구획별 필드 집합이 다릅니다** — 클라이언트 표시 방식이 달라 공유하지 않습니다.
					- `current` 35필드 — 회차명 · 소속 팀명 · 교안 · 4단계 진행 상태
					- `upcoming` 7필드 — 회차명 · 제출일자 · 이해도 확인 시작·종료일자
					- `past` 8필드 — 회차명 · 세션 완료 여부 · 다시 보기 상태

					**4단계 진행 표시**는 파생 필드 없이 원시 상태값으로 판정합니다.

					| 단계 | 완료 조건 |
					|---|---|
					| ① 코드 제출 | `submissionStatus = 'ACCEPTED'` |
					| ② 코드 분석 | `analysisPhase = 'COMPLETED'` |
					| ③ 이해도 확인 | `initialAttemptStatus = 'COMPLETED'` |
					| ④ 리포트 | `canViewReport = true` |

					**팀은 회차 스코프라 `current`에 있습니다.** 교육생은 회차마다 팀이 바뀌고
					(`team.project_id`가 NOT NULL), View가 회차의 `submission_due_at` 시점으로 소속을
					확정합니다. 기수·반만 `membership`에 둡니다.

					**`canViewReport`의 판정 근거는 `traineeReleaseStatus` 하나입니다.**
					`reportPublishStatus`는 리포트 발행 진행 상태라 열람 판정에 넣지 않습니다.

					**커밋 이메일 배너**는 `commitEmailStatus !== "VERIFIED"`일 때 띄웁니다.
					`null`은 미등록을 뜻하며, 미등록·미검증이 제출을 막지는 않습니다. 다만 빅프로젝트
					(`projectCategory = 'BIG_PROJECT'`)에서는 커밋 귀속이 비어 문제가 생성되지 않으므로
					배너 강도를 `projectCategory`로 구분하십시오.

					**페이지네이션은 없습니다.** 한 기수의 회차가 10건 미만이라 전량 반환합니다.
					"""
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = """
							조회 성공. **빈 결과라는 것은 없습니다** — 진행 회차가 없거나 기수에 소속되지 \
							않았으면 `current`에 `NO_ACTIVE_ROUND` 합성 카드가 들어가고 `membership`은 \
							조회 가능한 만큼 채워집니다.""",
					content = @Content(
							schema = @Schema(implementation = AssessmentRoundsResponse.class),
							examples = {
									@ExampleObject(
											name = "진행 회차 있음 (코드 분석 중)",
											value = """
													{
													  "membership": {
													    "cohortId": "6f1b7d2e-0c3a-4f5b-9a71-2d8e4c6b0a13",
													    "cohortName": "7기",
													    "classId": "8a2c9e41-77b5-4d0e-9c3f-1b6a5d7e2f84",
													    "className": "A반"
													  },
													  "current": {
													    "assessmentRoundId": "c4e5a1b8-3d92-4f07-8e16-5a7b9c0d2e3f",
													    "roundNo": 3,
													    "roundName": "미프 3차",
													    "roundStatus": "OPEN",
													    "projectId": "1d7f3b0a-9e64-4c28-b5a1-6f8e2c4d0b97",
													    "projectName": "미프 3차",
													    "projectCategory": "MINI_PROJECT",
													    "curriculumNames": ["AI_LLMOps"],
													    "teamId": "2b8d4f61-5a03-4e79-9c62-3f1a7e5b8d40",
													    "teamNumber": "3",
													    "teamName": "3팀",
													    "representativeStatus": "ANALYZING",
													    "defaultActionCode": "WAIT_FOR_ANALYSIS",
													    "actionUnavailableReasonCode": null,
													    "warningCodes": [],
													    "commitEmailStatus": "PENDING",
													    "availableSubmissionMethods": ["GITHUB_URL", "ZIP_WITH_GITLOG"],
													    "submissionMethod": "GITHUB_URL",
													    "submissionStatus": "ACCEPTED",
													    "submittedAt": "2026-07-14T08:22:10Z",
													    "canSubmit": true,
													    "canResubmit": true,
													    "analysisPhase": "ANALYZING",
													    "analysisJobStatus": "RUNNING",
													    "analysisFailureCode": null,
													    "initialAttemptStatus": "ANALYZING",
													    "initialSessionStatus": null,
													    "preparedProblemCount": 3,
													    "reviewStatus": null,
													    "completedReviewCount": 0,
													    "reportId": null,
													    "reportPublishStatus": "NOT_PUBLISHED",
													    "traineeReleaseStatus": "NOT_CONFIGURED",
													    "canViewReport": false,
													    "explanationStatus": "UNAVAILABLE",
													    "submissionDueAt": "2026-07-14T09:00:00Z",
													    "roundAssessmentOpenAt": "2026-07-14T15:00:00Z",
													    "roundAssessmentDueAt": "2026-07-15T14:59:59Z",
													    "assessmentOpenAt": null,
													    "assessmentCloseAt": null,
													    "initialTerminalAt": null,
													    "reportPublishMode": "ROUND_BATCH",
													    "reportPublishNotBeforeAt": "2026-07-15T14:59:59Z",
													    "manager": {
													      "userId": "9c0b5e28-4a76-4d31-8f92-7e3d1a6c5b04",
													      "name": "김매니저"
													    },
													    "asOfAt": "2026-08-06T00:14:02Z"
													  },
													  "upcoming": [
													    {
													      "assessmentRoundId": "5e9a2c74-6b18-4f30-a7d5-8c2f1b0e9d63",
													      "roundNo": 4,
													      "roundName": "미프 4차",
													      "roundStatus": "PLANNED",
													      "submissionDueAt": "2026-07-21T09:00:00Z",
													      "roundAssessmentOpenAt": "2026-07-21T15:00:00Z",
													      "roundAssessmentDueAt": "2026-07-23T14:59:59Z"
													    }
													  ],
													  "past": [
													    {
													      "assessmentRoundId": "7a3c8d15-2e60-4b94-8f71-0d5e9a2c6b38",
													      "roundNo": 2,
													      "roundName": "미프 2차",
													      "representativeStatus": "ASSESSMENT_COMPLETED",
													      "reviewStatus": null,
													      "completedReviewCount": 1,
													      "reportId": "3f6b0e97-8c24-4a15-9d38-2b7e5c1a4f60",
													      "canViewReport": true
													    }
													  ]
													}"""
									),
									@ExampleObject(
											name = "진행 회차 없음 (합성 카드)",
											value = """
													{
													  "membership": {
													    "cohortId": "6f1b7d2e-0c3a-4f5b-9a71-2d8e4c6b0a13",
													    "cohortName": "7기",
													    "classId": "8a2c9e41-77b5-4d0e-9c3f-1b6a5d7e2f84",
													    "className": "A반"
													  },
													  "current": {
													    "assessmentRoundId": null,
													    "roundNo": null,
													    "roundName": null,
													    "roundStatus": null,
													    "projectId": null,
													    "projectName": null,
													    "projectCategory": null,
													    "curriculumNames": [],
													    "teamId": null,
													    "teamNumber": null,
													    "teamName": null,
													    "representativeStatus": "NO_ACTIVE_ROUND",
													    "defaultActionCode": "NONE",
													    "actionUnavailableReasonCode": null,
													    "warningCodes": [],
													    "commitEmailStatus": null,
													    "availableSubmissionMethods": [],
													    "submissionMethod": null,
													    "submissionStatus": null,
													    "submittedAt": null,
													    "canSubmit": false,
													    "canResubmit": false,
													    "analysisPhase": "NOT_SUBMITTED",
													    "analysisJobStatus": null,
													    "analysisFailureCode": null,
													    "initialAttemptStatus": null,
													    "initialSessionStatus": null,
													    "preparedProblemCount": 0,
													    "reviewStatus": null,
													    "completedReviewCount": 0,
													    "reportId": null,
													    "reportPublishStatus": "NOT_PUBLISHED",
													    "traineeReleaseStatus": "NOT_CONFIGURED",
													    "canViewReport": false,
													    "explanationStatus": "UNAVAILABLE",
													    "submissionDueAt": null,
													    "roundAssessmentOpenAt": null,
													    "roundAssessmentDueAt": null,
													    "assessmentOpenAt": null,
													    "assessmentCloseAt": null,
													    "initialTerminalAt": null,
													    "reportPublishMode": null,
													    "reportPublishNotBeforeAt": null,
													    "manager": null,
													    "asOfAt": "2026-08-06T00:14:02Z"
													  },
													  "upcoming": [],
													  "past": []
													}"""
									)
							}
					)
			),
			@ApiResponse(
					responseCode = "401",
					description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
					responseCode = "403",
					description = "호출자가 교육생(TRAINEE)이 아님",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			)
	})
	@GetMapping
	public ResponseEntity<AssessmentRoundsResponse> getMyAssessmentRounds() {
		return ResponseEntity.ok(assessmentRoundQueryService.getMyAssessmentRounds());
	}
}
