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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Assessment", description = "교육생 이해도 확인 회차")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/assessment-rounds", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('TRAINEE')")
@RequiredArgsConstructor
public class AssessmentRoundController {

	private final AssessmentRoundQueryService assessmentRoundQueryService;

	@Operation(
			operationId = "getMyAssessmentRounds",
			summary = "교육생 홈 3구획 조회 | ✅ 사용 가능",
			description = """
					교육생 홈이 한 화면에 세 구획(지금 할 일 · 예정 · 지난 회차)을 그리므로 요청 하나로 묶는다.
					원천은 `trainee_home_round_view` 단일 조회이며 스코프는 경로가 아니라 **액세스 토큰에서
					도출**한다.

					**구획별 필드 집합이 다르다** — 클라이언트 표시 방식이 달라 공유하지 않는다.
					`current` 35필드 · `upcoming` 7필드 · `past` 8필드.

					## 요청

					경로·쿼리 파라미터가 **없다.** 조회 대상 교육생은 `Authorization` 헤더의 액세스 토큰에서
					정한다.

					**페이지네이션도 없다.** 한 기수의 회차가 10건 미만이라 전량 반환한다.

					## 응답 (200)

					**빈 결과라는 것은 없다.** 진행 회차가 없거나 기수에 소속되지 않았어도 네 필드가 모두 온다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `membership` | object | 기수·반 소속. 객체 자체는 항상 존재 |
					| `current` | object | 지금 할 일 카드. 진행 회차가 없으면 `NO_ACTIVE_ROUND` 합성 카드 |
					| `upcoming[]` | array | 예정 회차. 없으면 빈 배열 |
					| `past[]` | array | 지난 회차. 없으면 빈 배열 |

					### membership (object)

					**팀은 여기 없다.** 교육생은 회차마다 팀이 바뀌고(`team.project_id`가 NOT NULL) View가 회차의
					`submission_due_at` 시점으로 소속을 확정하므로, 팀은 회차 스코프인 `current`에 둔다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `cohortId` | UUID? | 기수 ID. 기수 미소속이면 이 객체의 전 필드가 `null` |
					| `cohortName` | string? | 기수 표시명. 예: `7기` |
					| `classId` | UUID? | 반 ID. 반 미배정이면 `null` |
					| `className` | string? | 반 표시명. 예: `A반` |

					### current (object) — 35필드

					회차가 없으면 서버가 합성 카드를 만든다. 이때 식별·일정 필드는 전부 `null`이고
					`representativeStatus=NO_ACTIVE_ROUND` · `defaultActionCode=NONE` · `analysisPhase=NOT_SUBMITTED`
					가 된다. **View는 이 상태를 만들지 못한다** — 진행 중인 프로젝트가 없으면 원천이 0행이다.

					**회차·프로젝트**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID? | 합성 카드일 때만 `null` |
					| `roundNo` | int? | 회차 번호 |
					| `roundName` | string? | 회차명. 예: `미프 3차` |
					| `roundStatus` | enum? | 실제 회차면 항상 `OPEN` |
					| `projectId` | UUID? | 프로젝트 ID |
					| `projectName` | string? | 프로젝트명 |
					| `projectCategory` | enum? | `MINI_PROJECT` · `BIG_PROJECT` |
					| `curriculumNames[]` | array | 교안 표시명. 없으면 빈 배열 |

					**팀 (회차 스코프)**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `teamId` | UUID? | 팀 미편성이면 `null` |
					| `teamNumber` | string? | 팀 번호. 예: `3` |
					| `teamName` | string? | 팀명. 예: `3팀` |

					**대표 상태·액션**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `representativeStatus` | enum | 카드 배지. 값 집합은 아래 표 |
					| `defaultActionCode` | enum | 기본 버튼. 값 집합은 아래 표 |
					| `actionUnavailableReasonCode` | enum? | `SUBMISSION_DEADLINE_PASSED` · `ASSESSMENT_WINDOW_CLOSED`. 없으면 `null` |
					| `warningCodes[]` | array | 경고 배지. 없으면 빈 배열. 값 집합은 아래 표 |

					**제출**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `commitEmailStatus` | enum? | **`null`은 미등록**을 뜻한다. 아래 배너 규칙 참고 |
					| `availableSubmissionMethods[]` | array | 기관 정책이 허용한 수단. `GITHUB_URL` · `ZIP_WITH_GITLOG` |
					| `submissionMethod` | enum? | 실제 제출 수단. 미제출이면 `null` |
					| `submissionStatus` | enum? | `VALIDATING` · `ACCEPTED` · `FETCH_FAILED` · `INVALID` |
					| `submittedAt` | datetime? | 제출 시각 (ISO-8601 UTC) |
					| `canSubmit` | boolean | 마감 전이고 아직 세션을 시작하지 않았으면 `true` |
					| `canResubmit` | boolean | 위 조건 + 이미 제출이 있으면 `true` |

					**분석**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `analysisPhase` | enum | `NOT_SUBMITTED` · `ANALYZING` · `FAILED` · `COMPLETED` · `WAITING` |
					| `analysisJobStatus` | enum? | `QUEUED` · `RUNNING` · `SUCCEEDED` · `PARTIAL` · `FAILED` |
					| `analysisFailureCode` | string? | 분석 실패 사유. 15종. 상세는 Submission API 참고 |

					**이해도 확인·다시 보기**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `initialAttemptStatus` | enum? | `NOT_STARTED` · `SUBMITTED` · `ANALYZING` · `SESSION_READY` · `SESSION_IN_PROGRESS` · `COMPLETED` · `FAILED` · `EXPIRED` |
					| `initialSessionStatus` | enum? | `READY` · `IN_PROGRESS` · `PAUSED` · `COMPLETED` 등 |
					| `preparedProblemCount` | int | 출제된 문제 수. 보통 `3` |
					| `reviewStatus` | enum? | 다시 보기 상태. 배정이 없으면 `null` |
					| `completedReviewCount` | int | 완료한 다시 보기 건수 |

					**리포트**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `reportId` | UUID? | 리포트 식별자 |
					| `reportPublishStatus` | enum | `PUBLISHED` · `GENERATING` · `NOT_PUBLISHED` |
					| `traineeReleaseStatus` | enum | 리포트 행이 없으면 `NOT_CONFIGURED`로 정규화한다 |
					| `canViewReport` | boolean | `traineeReleaseStatus=RELEASED`일 때만 `true` |
					| `explanationStatus` | enum | `UNAVAILABLE` · `PARTIAL` · `AVAILABLE` |

					**일정**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `submissionDueAt` | datetime? | 제출 마감 |
					| `roundAssessmentOpenAt` | datetime? | 회차 응시 창 시작. `OPEN` 회차면 DB가 non-null을 보장 |
					| `roundAssessmentDueAt` | datetime? | 회차 응시 창 종료. 위와 같음 |
					| `assessmentOpenAt` | datetime? | **개인** 응시 창 시작. 응시 생성 전이면 `null` |
					| `assessmentCloseAt` | datetime? | **개인** 응시 창 종료. 응시 생성 전이면 `null` |
					| `initialTerminalAt` | datetime? | 응시 종료 시각 |
					| `reportPublishMode` | enum? | `ROUND_BATCH` |
					| `reportPublishNotBeforeAt` | datetime? | 이 시각 이전에는 발행하지 않는다 |

					**기타**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `manager` | object? | `{ userId, name }`. 반에 활성 배정이 없으면 객체 자체가 `null` |
					| `asOfAt` | datetime | 서버 조회 시각 |

					#### representativeStatus 값 집합

					위에서부터 먼저 맞는 것 하나로 정해진다.

					| 값 | 언제 |
					| --- | --- |
					| `REVIEW_REQUIRED` | 다시 보기 배정이 있고 아직 끝나지 않았다 |
					| `ASSESSMENT_COMPLETED` | 응시를 완료했다 |
					| `ASSESSMENT_WINDOW_CLOSED` | 미응시·중단이거나 개인 응시 창이 닫혔다 |
					| `ASSESSMENT_IN_PROGRESS` | 세션을 풀고 있거나 일시정지했다 |
					| `ASSESSMENT_AVAILABLE` | 응시할 수 있다 |
					| `ANALYSIS_FAILED` | 분석이 실패했다 |
					| `SUBMISSION_MISSED` | 마감까지 제출하지 않았다 |
					| `SUBMISSION_REQUIRED` | 아직 제출하지 않았다(마감 전) |
					| `ANALYZING` | 위 어디에도 해당하지 않는다(제출 후 분석 중) |
					| `NO_ACTIVE_ROUND` | 진행 회차가 없다. **서버 합성 카드 전용** |

					#### defaultActionCode 값 집합

					| 값 | 화면 동작 |
					| --- | --- |
					| `VIEW_REPORT` | 리포트 보기 |
					| `WAIT_FOR_REPORT` | 리포트 발행 대기 |
					| `START_REVIEW` | 다시 보기 시작 |
					| `RESUME_ASSESSMENT` | 응시 이어하기 |
					| `START_ASSESSMENT` | 응시 시작 |
					| `RESUBMIT_REPOSITORY` | 저장소 재제출 (분석 실패 + 마감 전) |
					| `RESUBMIT_ZIP` | ZIP 재업로드 (분석 실패 + 마감 전) |
					| `CONTACT_MANAGER` | 매니저 문의 (복구 경로가 없다) |
					| `SUBMIT_CODE` | 코드 제출 |
					| `WAIT_FOR_ANALYSIS` | 분석 대기 |
					| `NONE` | 할 일이 없다 |

					#### warningCodes 값 집합

					배열이므로 **여러 개가 동시에 올 수 있다.**

					| 값 | 언제 |
					| --- | --- |
					| `SUBMISSION_DEADLINE_PASSED` | 제출 마감이 지났다 |
					| `ANALYSIS_FAILED` | 분석이 실패했다 |
					| `ASSESSMENT_WINDOW_CLOSED` | 응시 창이 닫혔는데 완료하지 못했다 |
					| `PROBLEM_NOT_GENERATED` | 코드 근거가 부족해 문항을 만들지 못했다 |

					### upcoming[] 각 항목 — 7필드

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID |
					| `roundNo` | int | 회차 번호 |
					| `roundName` | string | 회차명. 예: `미프 4차` |
					| `roundStatus` | enum | 항상 `PLANNED` |
					| `submissionDueAt` | datetime | 제출 마감 |
					| `roundAssessmentOpenAt` | datetime? | 이해도 확인 시작. **`PLANNED`에서는 `null`일 수 있다** |
					| `roundAssessmentDueAt` | datetime? | 이해도 확인 종료. 위와 같은 이유로 `null` 가능 |

					⚠️ 두 일정이 `null`일 수 있는 이유는 `ck_project_assessment_round_assessment_window_required`가
					`PLANNED` 회차만 면제하기 때문이다.

					### past[] 각 항목 — 8필드

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID |
					| `roundNo` | int | 회차 번호 |
					| `roundName` | string | 회차명. 예: `미프 2차` |
					| `representativeStatus` | enum | 세션 완료 여부 판정용. `ASSESSMENT_COMPLETED`이면 완료 |
					| `reviewStatus` | enum? | 다시 보기 상태. 배정이 없으면 `null` |
					| `completedReviewCount` | int | 완료한 다시 보기 건수 |
					| `reportId` | UUID? | 리포트 식별자 |
					| `canViewReport` | boolean | `traineeReleaseStatus=RELEASED`일 때만 `true` |

					⚠️ "완료 여부" boolean은 **일부러 두지 않는다.** `representativeStatus`가 완료와 미완료 사유를
					이미 구분하므로, 파생값을 더하면 계약이 둘로 갈린다.

					## 화면 규칙

					### 4단계 진행 표시

					파생 필드 없이 원시 상태값으로 클라이언트가 판정한다.

					| 단계 | 완료 조건 |
					| --- | --- |
					| ① 코드 제출 | `submissionStatus = 'ACCEPTED'` |
					| ② 코드 분석 | `analysisPhase = 'COMPLETED'` |
					| ③ 이해도 확인 | `initialAttemptStatus = 'COMPLETED'` |
					| ④ 리포트 | `canViewReport = true` |

					### 리포트 열람

					**`canViewReport`의 판정 근거는 `traineeReleaseStatus` 하나다.** `reportPublishStatus`는 리포트
					발행 진행 상태라 열람 판정에 넣지 않는다.

					### 커밋 이메일 배너

					`commitEmailStatus !== "VERIFIED"`일 때 띄운다. `null`은 미등록을 뜻하며, 미등록·미검증이
					제출을 막지는 않는다. 다만 빅프로젝트(`projectCategory = 'BIG_PROJECT'`)에서는 커밋 귀속이
					비어 문제가 생성되지 않으므로 배너 강도를 `projectCategory`로 구분한다.

					## 오류

					| 상태 | 언제 |
					| --- | --- |
					| 401 | 액세스 토큰이 없거나 인증 사용자를 찾을 수 없다 |
					| 403 | 호출자가 교육생(`TRAINEE`)이 아니다 |
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
