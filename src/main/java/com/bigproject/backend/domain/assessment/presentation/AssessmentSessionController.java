package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.application.AssessmentReviewService;
import com.bigproject.backend.domain.assessment.application.AssessmentSessionService;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionActivityEventRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionActivityRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.HintResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ReviewOpenRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionResponse;
import com.bigproject.backend.global.exception.ErrorResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 검증 세션(TR-03). 전체화면 · 네비 없음 · 나가는 경로 없음.
 *
 * <h2>경로가 다섯인 이유</h2>
 *
 * <p>세션 하나에 필요한 동작은 <b>이어서 하기 · 시작 · 문제 열기 · 답변 · 다시 설명</b> 다섯이다.
 * 답변에 문제·질문을 싣지 않는 것이 이 설계의 핵심이다 — 진행 위치는 서버 커서가 정본이고, 그래야
 * 새로고침·재접속이 별도 복구 API 없이 {@code GET /current} 하나로 해결된다.
 *
 * <p>다시 설명을 답변과 나눈 것은 성격이 다르기 때문이다. 답변은 AI 채점이라 몇 초가 걸리고 실패하면
 * 재전송을 요구하지만, 다시 설명은 동결된 문구를 꺼내는 즉답이고 몇 번을 눌러도 같다.
 */
@Tag(name = "Assessment", description = "교육생 이해도 확인 회차")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('TRAINEE')")
@Validated
@RestController
@RequestMapping(value = "/assessment-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AssessmentSessionController {

	/** 분산 추적 ID. AI로 그대로 넘어가 {@code ai_usage.trace_id}로 돌아온다. */
	private static final String TRACE_ID_HEADER = "X-Request-Id";

	private final AssessmentSessionService sessionService;
	private final AssessmentReviewService reviewService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findCurrentSession",
			summary = "지금 이어서 할 세션 조회 | ✅ 사용 가능",
			description = """
					TR-03 진입과 **복귀**를 함께 처리한다. 진행 중인 세션이 있으면 그것을, 없으면 시작할 수 있는
					세션을 준다 — 새로고침·브라우저 종료 후 재접속이 이 경로 하나로 해결되므로 별도 복구 API가 없다.

					## 요청

					파라미터가 없다. 대상은 **액세스 토큰의 사용자**에서 도출한다(경로로 받지 않는다).

					요청 본문도 없다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `sessionId` | UUID | 이후 네 경로가 모두 이 값을 쓴다 |
					| `mode` | enum | `FIRST`(1차) · `REVIEW`(다시 보기). REVIEW는 판정에 반영되지 않는다(힌트는 1차와 같다) |
					| `status` | enum | `READY`(시작 전 안내) · `IN_PROGRESS`(진행 중) |
					| `currentProblemNo` | int? | 지금 서 있는 문제 번호(1~`problemTotal`). 시작 전이면 `null` |
					| `problemTotal` | int | 생성된 문제 수. 화면의 `문제 n/N`의 N |
					| `startedAt` | date-time? | 경과 시간 표시의 기산점. 시작 전이면 `null` |
					| `timeLimitAt` | date-time? | 정책 시간 상한. 상한이 없으면 `null` |
					| `reviewDueAt` | date-time? | 다시 보기 마감. `mode=FIRST`이면 `null` |

					### 🔴 `null`인 필드는 키 자체가 없다

					이 응답은 `NON_NULL` 직렬화라 값이 없는 필드가 `"startedAt": null`로 오지 않고 **키가 통째로
					빠진다.** 화면은 `response.startedAt === null`이 아니라 `== null`(undefined 포함)로 판정해야
					한다 — 아래 세 예시가 같은 스키마의 서로 다른 모양이다.

					### 상황에 따라 세 가지 몸이 온다

					| 상황 | `status` | 무엇이 다른가 |
					|---|---|---|
					| 시작 전 | `READY` | `currentProblemNo`·`startedAt`·`timeLimitAt` 키가 없다 |
					| 진행 중 복귀 | `IN_PROGRESS` | 커서와 시각이 모두 채워져 있다 |
					| 다시 보기 | `READY`·`IN_PROGRESS` | `mode=REVIEW`이고 `reviewDueAt`이 붙는다 |

					⚠️ `problemTotal`은 **3이 아닐 수 있다.** 코드에 근거가 없어 문항이 만들어지지 않은 개념
					(`NOT_GENERATED`)에는 단계를 만들지 않으므로 화면의 `n/3` 하드코딩은 틀린다.

					💡 **번호는 생성된 문제만 1부터 센다.** 원본 `assessment_problem.problem_no`에는 빈틈이
					생길 수 있으나(1번이 `NOT_GENERATED`면 2·3만 남는다) 세션 API는 그것을 다시 매겨
					`1~problemTotal`을 준다. 그래서 화면은 **받은 번호를 그대로** 쓰면 되고, 생성되지 않은
					문제는 애초에 열 수 없다.

					진행 중인 세션을 다시 보기보다 먼저 고른다. 둘 다 없으면 **`204 No Content`**이며 화면은
					`진행 중인 회차 없음`으로 그린다.

					## 🔴 이 조회가 시간 상한을 정리한다

					조회이지만 **읽기만 하지 않는다.** 상한을 넘긴 세션·문제를 이 자리에서 닫고, 그 결과를
					반영한 상태를 돌려준다.

					| 넘긴 상한 | 이 조회가 하는 일 | 응답 |
					|---|---|---|
					| 세션(`timeLimitAt`) | 세션을 `INTERRUPTED`로 닫는다 | **204**(다른 살아 있는 세션이 없으면) |
					| 문제(시작 + 20분) | 그 문제를 접고 다음 문제로 커서를 옮긴다 | 200. `currentProblemNo`가 다음 번호 |

					그래서 **학생이 아무것도 제출하지 않고 새로고침만 해도** 상한이 반영된다. 종전에는 쓰기
					요청만 상한을 봐서, 끝났어야 할 세션이 계속 진행 중으로 내려가고 남은 시간이 음수인 화면이
					그려졌다(2026-08-15 교정).

					마지막 문제가 상한을 넘기면 그 자리에서 세션이 끝나므로 **204**가 온다.

					## 204를 "응시 완료"로 읽지 말 것

					204는 **살아 있는 세션(`READY`·`IN_PROGRESS`·`PAUSED`)이 하나도 없다**는 뜻일 뿐이고 그 이유는
					여섯 가지다 — 이미 완료했다 · **방금 상한을 넘겨 닫혔다** · **응시 창이 닫혔다** ·
					아직 분석이 끝나지 않아 세션이 만들어지지 않았다 · 분석이 실패했다 · 팀 배정이 끊겼다.
					이들을 가르는 것은 `GET /assessment-rounds`의 `representativeStatus`이며, 완료는 그중
					`ASSESSMENT_COMPLETED` 하나다. 상한 초과로 닫힌 세션은 응시가 `SESSION_INCOMPLETE`로 끝나
					`initialSessionStatus`가 `INTERRUPTED`다.

					**응시 창(`assessmentCloseAt`)이 닫혔으면 204다**(2026-08-16 변경). 종전에는 창이 지난
					`READY` 세션을 200으로 내려줬는데, 화면이 시작할 수 없는 세션을 "지금 할 일"로 그려 놓고
					`POST /start`에서만 막히는 상태였다. 다시 보기도 같다 — `reviewDueAt`이 지나면 204다.
					그 회차의 홈 카드는 `ASSESSMENT_WINDOW_CLOSED` · CTA `NONE`으로 나간다.

					## 오류

					| 상태 | 언제 |
					|---|---|
					| 401 | 액세스 토큰이 없거나 만료됐다 |
					| 403 | 호출자가 교육생(`TRAINEE`)이 아니다 |""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "이어서 할 세션이 있다. 세 가지 몸 중 하나가 온다",
					content = @Content(
							schema = @Schema(implementation = SessionResponse.class),
							examples = {
									@ExampleObject(
											name = "시작 전 (READY)",
											description = "인트로 화면. 커서·시각 키가 아예 없다",
											value = """
													{
													  "sessionId": "3f6d1c40-8b2e-4a19-9f57-0d2e6b8c4a51",
													  "mode": "FIRST",
													  "status": "READY",
													  "problemTotal": 3
													}"""),
									@ExampleObject(
											name = "진행 중 복귀 (IN_PROGRESS)",
											description = "새로고침·재접속. 커서가 서 있던 자리 그대로다",
											value = """
													{
													  "sessionId": "3f6d1c40-8b2e-4a19-9f57-0d2e6b8c4a51",
													  "mode": "FIRST",
													  "status": "IN_PROGRESS",
													  "currentProblemNo": 2,
													  "problemTotal": 3,
													  "startedAt": "2026-08-15T04:02:11Z",
													  "timeLimitAt": "2026-08-15T05:02:11Z"
													}"""),
									@ExampleObject(
											name = "문항이 1개뿐 (NOT_GENERATED 2건)",
											description = "problemTotal은 3이 아닐 수 있다. 화면의 n/3 하드코딩은 틀린다",
											value = """
													{
													  "sessionId": "3f6d1c40-8b2e-4a19-9f57-0d2e6b8c4a51",
													  "mode": "FIRST",
													  "status": "IN_PROGRESS",
													  "currentProblemNo": 1,
													  "problemTotal": 1,
													  "startedAt": "2026-08-15T04:02:11Z",
													  "timeLimitAt": "2026-08-15T05:02:11Z"
													}"""),
									@ExampleObject(
											name = "다시 보기 (REVIEW)",
											description = "힌트가 없고 판정에 반영되지 않는다. reviewDueAt이 붙는다",
											value = """
													{
													  "sessionId": "9c1a7e33-42b6-4d80-8e15-7a3f2b9d6c04",
													  "mode": "REVIEW",
													  "status": "READY",
													  "problemTotal": 2,
													  "reviewDueAt": "2026-08-18T14:59:59Z"
													}""")
							})),
			@ApiResponse(
					responseCode = "204",
					description = """
							이어서 할 세션이 없다. **본문이 없다** — 완료·상한 초과로 방금 닫힘·미준비·분석 \
							실패를 구분하지 않는다. 사유는 `GET /assessment-rounds`로 가른다""",
					content = @Content),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@GetMapping("/current")
	public ResponseEntity<SessionResponse> findCurrent() {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return sessionService.findCurrent(userId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@Operation(
			operationId = "openReviewSession",
			summary = "다시 보기 개설(리포트에서 파생) | ✅ 사용 가능",
			description = """
					공개된 리포트를 근거로 **다시 보기 응시를 만든다.** 이 경로가 없으면 다시 보기는 존재할 수
					없다 — `measurement_attempt`를 만드는 다른 두 자리는 모두 `INITIAL` 고정이다.

					## 무엇을 다시 보는가

					**도달 단계가 2단 미만인 문제**만 골라 그 문제의 **첫 번째 질문(L1)부터** 다시 본다.
					2단(설계 논리)이 합격선이므로 그 미만이 다시 볼 대상이고, 리포트가 `retryTarget`으로
					표시하는 개념과 **같은 규칙·같은 기준값**이다.

					| 1차 도달 단계 | 다시 보기 |
					|---|---|
					| 0단(통과한 축 없음) · 1단 | **대상.** L1~L4를 처음부터 |
					| 2단 이상 | 대상 아님. 세션에 나오지 않는다 |

					도달 단계는 `problem_stage`에서 센다(통과한 가장 높은 축). 미응시로 단계가 전부
					`NOT_REACHED`인 문제도 0단이라 대상이다.

					## 문제와 질문은 1차와 완전히 같다

					AI를 다시 부르지 않고 1차 세션의 `problem_stage`를 **그대로 복사한다** — 같은 문제,
					같은 질문, 같은 힌트 문구다. 새로 만들면 문구가 달라져 1차와 도달 단계를 비교할 수
					없게 되고(매니저 지표가 그 비교를 읽는다) 비용도 다시 나간다.

					답변·점수는 복사하지 않는다. 복사된 단계는 `source_problem_stage_id`로 원본을 가리키며,
					매니저 화면의 `0단 → 1단` 비교가 이 연결을 따라간다.

					## 요청 (본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `reportId` | 필수 | UUID | 근거 리포트. `GET /assessment-rounds`의 `current.reportId` |

					리포트는 **본인 것이고 공개된 것**이어야 한다(`lifecycle_status=ACTIVE` ·
					발행된 리포트여야 한다). 회차·1차 응시는 리포트에서 도출하므로 따로 받지 않는다.

					스키마가 리포트를 요구한다 — `ck_measurement_attempt_attempt_type_2`가 REVIEW에
					리포트 ID와 스냅샷 ID를 둘 다 NOT NULL로 못박고 있다.

					## 응답 (201)

					**`GET /current`와 같은 구조**(`SessionResponse`)다. `mode`가 `REVIEW`이고 `status`는
					`READY`, `reviewDueAt`이 채워진다. `problemTotal`은 **다시 볼 문제 수**라 1차보다 작다.

					만든 다음은 1차와 똑같다 — 받은 `sessionId`로 `POST /{sessionId}/start`를 부르면 된다.
					커서·인트로 동의·문제별 20분 시계는 그쪽이 세운다.

					**힌트도 1차와 똑같다.** `POST /{sessionId}/hints`가 그대로 열리고 미달 시 자동 지급도 돈다
					(2026-08-18 변경). 종전에는 409 `HINT_NOT_AVAILABLE`로 막았는데, 그러면 미달한 축에서
					다음 슬롯이 열리지 않아 세션이 그 자리에 갇혔다. 다만 문답·힌트가 1차의 복사본이라
					**1차에서 이미 본 힌트를 다시 보게 된다** — 새 힌트가 생기지는 않는다.

					## 두 번 눌러도 안전하다

					이미 열려 있는 다시 보기가 있으면 **새로 만들지 않고 그것을 돌려준다.** 다시 보기는
					회차당 한 번이라, 두 번 눌러 응시가 둘 생기면 도달 단계 비교가 어느 쪽을 봐야 하는지
					알 수 없게 된다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `REVIEW_REPORT_NOT_ACCESSIBLE` | 404 | 리포트가 없거나·남의 것이거나·아직 공개되지 않았다 |
					| `REVIEW_SOURCE_NOT_READY` | 409 | 1차 응시가 끝나지 않았다. 도달 단계가 확정되지 않았다 |
					| `REVIEW_NOT_ELIGIBLE` | 409 | 2단 미만인 문제가 없다. **다시 볼 것이 없다** |
					| `REVIEW_ALREADY_COMPLETED` | 409 | 이 회차의 다시 보기를 이미 끝냈다 |

					`REVIEW_NOT_ELIGIBLE`은 실패가 아니라 **안내**다. 화면은 `다시 볼 개념이 없어요`로
					그리면 된다 — 리포트의 `retryTarget`이 전부 `false`인 경우와 같은 상태다.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "다시 보기 개설됨. 이미 열려 있었으면 그것을 그대로 돌려준다",
					content = @Content(
							schema = @Schema(implementation = SessionResponse.class),
							examples = {
									@ExampleObject(
											name = "새로 개설 (2단 미만 2문제)",
											description = "1차는 3문제였지만 다시 볼 것은 2문제다",
											value = """
													{
													  "sessionId": "9c1a7e33-42b6-4d80-8e15-7a3f2b9d6c04",
													  "mode": "REVIEW",
													  "status": "READY",
													  "problemTotal": 2,
													  "reviewDueAt": "2026-08-22T04:02:11Z"
													}"""),
									@ExampleObject(
											name = "이미 열려 있음 (재클릭)",
											description = "새 응시를 만들지 않는다. 진행 중이었으면 커서가 그대로다",
											value = """
													{
													  "sessionId": "9c1a7e33-42b6-4d80-8e15-7a3f2b9d6c04",
													  "mode": "REVIEW",
													  "status": "IN_PROGRESS",
													  "currentProblemNo": 2,
													  "problemTotal": 2,
													  "startedAt": "2026-08-16T01:10:00Z",
													  "timeLimitAt": "2026-08-16T02:10:00Z",
													  "reviewDueAt": "2026-08-22T04:02:11Z"
													}""")
							})),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED · reportId가 없다",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "REVIEW_REPORT_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "REVIEW_SOURCE_NOT_READY · REVIEW_NOT_ELIGIBLE · REVIEW_ALREADY_COMPLETED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping(value = "/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SessionResponse> openReview(@Valid @RequestBody ReviewOpenRequest request) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(reviewService.openReview(userId, request.reportId()));
	}

	@Operation(
			operationId = "startSession",
			summary = "세션 시작(인트로 동의) | ✅ 사용 가능",
			description = """
					시작 전 안내에서 `전체화면으로 시작하기`를 눌렀을 때 부른다. `READY → IN_PROGRESS`로 옮기고
					**인트로 고지 동의를 함께 남긴다** — 무효 응시 검토에서 "그때 무엇을 고지받았나"를 이 기록으로
					되짚기 때문에 선택이 아니다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | `GET /current`가 준 값 |

					본문은 없다.

					## 응답 (200)

					`GET /current`와 **같은 구조**(`SessionResponse`)다. `status`가 `IN_PROGRESS`로 바뀌고
					`startedAt`·`timeLimitAt`이 채워진다. 커서는 첫 문제의 L1에 선다.

					### 성공 몸이 두 가지다

					| 상황 | 무엇이 다른가 |
					|---|---|
					| 처음 눌렀다 | `startedAt`이 방금 시각. `currentProblemNo=1` |
					| 이미 진행 중이었다 | **그대로 돌려준다.** `startedAt`은 최초 시각이고 `currentProblemNo`는 서 있던 자리 |

					두 번째가 있는 이유는 새로고침 후 다시 눌러도 커서가 처음으로 돌아가지 않아야 하기
					때문이다. 화면은 둘을 구분할 필요가 없다 — 받은 커서로 그리면 된다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `SESSION_NOT_ACCESSIBLE` | 404 | 없거나 남의 세션 |
					| `SESSION_ALREADY_ENDED` | 409 | 이미 끝난 세션 |
					| `ASSESSMENT_WINDOW_CLOSED` | 409 | 개인 응시 창(`assessmentCloseAt`)이 닫혔다. **시작할 수 없다** |
					| `REVIEW_DUE_AT_PASSED` | 409 | 다시 보기 마감(`reviewDueAt`)이 지났다 |
					| `STAGE_NOT_FOUND` | 409 | 세울 단계가 없다. 문항이 하나도 만들어지지 않은 세션이다 |

					`STAGE_NOT_FOUND`는 문제 3개가 전부 `NOT_GENERATED`일 때 나온다. 200을 받고 전체화면으로
					넘어갔는데 서버는 시작되지 않은 상태로 남는 것을 막기 위해, 갱신 건수가 아니라 **최종 상태를
					다시 읽어** 판정한다.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "시작됨. 이미 진행 중이었으면 그 상태를 그대로 돌려준다",
					content = @Content(
							schema = @Schema(implementation = SessionResponse.class),
							examples = {
									@ExampleObject(
											name = "처음 시작",
											value = """
													{
													  "sessionId": "3f6d1c40-8b2e-4a19-9f57-0d2e6b8c4a51",
													  "mode": "FIRST",
													  "status": "IN_PROGRESS",
													  "currentProblemNo": 1,
													  "problemTotal": 3,
													  "startedAt": "2026-08-15T04:02:11Z",
													  "timeLimitAt": "2026-08-15T05:02:11Z"
													}"""),
									@ExampleObject(
											name = "이미 진행 중 (새로고침 후 재클릭)",
											description = "커서가 처음으로 돌아가지 않는다",
											value = """
													{
													  "sessionId": "3f6d1c40-8b2e-4a19-9f57-0d2e6b8c4a51",
													  "mode": "FIRST",
													  "status": "IN_PROGRESS",
													  "currentProblemNo": 2,
													  "problemTotal": 3,
													  "startedAt": "2026-08-15T04:02:11Z",
													  "timeLimitAt": "2026-08-15T05:02:11Z"
													}""")
							})),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SESSION_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "SESSION_ALREADY_ENDED · ASSESSMENT_WINDOW_CLOSED · REVIEW_DUE_AT_PASSED · STAGE_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping("/{sessionId}/start")
	public ResponseEntity<SessionResponse> start(@PathVariable UUID sessionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.start(userId, sessionId));
	}

	@Operation(
			operationId = "findSessionProblem",
			summary = "문제 하나의 코드·질문·문답 조회 | ✅ 사용 가능",
			description = """
					왼쪽 코드 패널과 오른쪽 대화가 이 한 번의 조회로 채워진다.

					## 🔴 교육생 본인 전용이다 (30차 Q1)

					**매니저는 부를 수 없다.** 두 겹으로 막혀 있다 — 컨트롤러 전체가 `TRAINEE` 역할을
					요구하고(403), 통과하더라도 서비스가 **세션 소유자 본인**인지 확인한다.
					`사용 가능` 표시는 "교육생 화면에서 쓸 수 있다"는 뜻이었고 역할을 적지 않은 것은
					누락이다.

					💡 **매니저가 「개념별 근거 한 줄」을 찾고 있다면 다른 조회에 이미 있다** —
					`GET /projects/{projectId}/evaluations/{userId}`(`findTraineeEvaluationDetail`,
					매니저 전용)의 `concepts[].steps[].note`가 축(L1~L4)별 채점 근거다.
					교육생 한 명당 **1콜**이며 문항 수만큼 부를 필요가 없다.
					`note`는 회차 리포트 발행 전에는 `null`이다(`reportPublished`로 분기).

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 세션 식별자 |
					| `problemNo` | 필수 | int | 문제 번호 `1`~`problemTotal`. `GET /current`·`POST /answers`가 준 값을 그대로 쓴다 |

					💡 번호는 **생성된 문항만 1부터 센 값**이다. 문항이 만들어지지 않은 개념
					(`NOT_GENERATED`)은 세션에 나오지 않으므로 이 경로로 열 수도 없다 —
					화면이 `1..problemTotal`을 순서대로 부르면 빈 번호에 걸리지 않는다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `problemNo` | int | 문제 번호 |
					| `problemTotal` | int | 생성된 문제 수. 화면의 `문제 n/N` |
					| `title` | string | 문제 제목. 검증하는 교안 개념 이름이다 |
					| `code` | object | 코드 패널. 구조는 아래 |
					| `turns[]` | array | 이 문제에서 지금까지 확정된 문답. 화면은 위에서 아래로 쌓는다 |
					| `current` | object? | 지금 물어보는 질문. 문제가 끝났으면 `null` |
					| `problemStartedAt` | date-time? | 이 문제의 기산점. **지금 문제일 때만** 값이 있다 |
					| `problemTimeLimitAt` | date-time? | 이 문제의 제한 시각. 화면의 문제별 카운트다운은 여기서 잰다 |

					⏱️ **문제별 카운트다운은 `problemTimeLimitAt`에서 잰다**(2026-08-18 추가). 세션 시작
					시각(`startedAt`)에서 재면 두 번째 문제부터 전부 틀린다 — 문제마다 20분을 새로 세기
					때문이다. 서버가 문제를 접는 판정도 같은 값을 쓰므로 화면과 판정이 갈리지 않는다.
					이미 끝난 문제를 열어 볼 때는 두 키가 모두 빠진다.

					**code**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `path` | string | 파일 경로 |
					| `language` | string | `PYTHON` · `JAVA` … 모르는 확장자는 `UNKNOWN` |
					| `snippet` | string | **문제를 낸 파일 전체.** 자를 위치는 화면이 정한다 |
					| `lineStart` | int | `snippet` 첫 줄의 파일 기준 절대 줄 번호. 화면은 여기서부터 번호를 매긴다 |
					| `lineEnd` | int | `snippet` 마지막 줄의 절대 줄 번호. **`snippet`에서 도출한다** — `lineEnd - lineStart + 1`이 곧 줄 수다 |

					⚠️ **`highlight`는 `snippet` 밖으로 나가지 않는다**(2026-08-18 보정). 저장된 좌표가
					`snippet`보다 길게 적혀 있는 경우가 있어(37차 R4), 서버가 `snippet` 마지막 줄로 잘라
					내려보낸다. 화면은 받은 값을 그대로 믿고 칠하면 된다.
					| `references[]` | array | `{ type, path, lineStart, lineEnd, axisCode }`. 호출부·관련 문맥. 화면은 접어 두고 필요할 때 편다 |

					**turns[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `sequenceNo` | int | 질문 순번. 화면의 `◆ 질문 2` |
					| `questionText` | string | 질문 원문 |
					| `hintText` | string? | 이 턴 직전에 보여준 힌트. 첫 시도면 `null` |
					| `answerText` | string | 학생 답변 원문 |
					| `answeredAt` | date-time | 제출 시각 |
					| `highlight` | object | `{ path, lineStart, lineEnd }`. 질문마다 옮겨간다 |

					**current**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `sequenceNo` | int | 질문 순번 |
					| `questionText` | string | 질문 원문 |
					| `shownHints[]` | array | 이미 연 힌트 문구. 없으면 빈 배열 |
					| `hintsUsed` | int | 지금까지 쓴 힌트 수(0~2) |
					| `hintsLeft` | int | 남은 힌트 수. 다시 보기도 1차와 같다 |
					| `highlight` | object | 강조 구간 |
					| `lastTurnOfSession` | boolean | `true`면 버튼이 `답변 제출하고 마치기`로 바뀐다 |

					⚠️ **점수·통과 여부는 응답에 없다**(정의서 §7 "세션 중에는 아무 판정도 안 보여준다").
					화면이 안 그려도 응답에 있으면 개발자 도구로 보이고, 그 순간 학생은 다음 답을 점수에 맞춰 쓴다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `PROBLEM_NOT_FOUND` | 404 | 그 번호의 문제가 이 세션에 없다 |
					| `PROBLEM_ALREADY_CLOSED` | 409 | **지금 문제가 아니다.** 진행 중에는 커서가 선 문제만 열린다 |

					`PROBLEM_ALREADY_CLOSED`는 정의서 §3 때문이다 — 끝난 문제를 다시 열면 지금 문제와 무관한 데
					시간을 쓰고 "아까 그거 틀린 것 같은데"만 남는다. 아직 시작하지 않은 뒤 문제도 같은 이유로 막는다.
					**세션이 끝난 뒤에는 전부 열린다.**

					## 성공 몸이 세 가지다

					| 상황 | `turns[]` | `current` |
					|---|---|---|
					| 문제에 처음 들어왔다 | 빈 배열 | 첫 질문(L1) |
					| 답을 쌓는 중 | 확정된 문답 | 지금 질문 |
					| 문제가 끝났다(종료 후 열람) | 전부 | **키 없음** |

					`current`는 `null`로 오지 않고 **키가 통째로 빠진다**(`NON_NULL` 직렬화). `turns[]`의
					`hintText`도 마찬가지라, 첫 시도 턴에는 그 키가 없다.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "문제 한 벌. 진행 상황에 따라 turns·current가 달라진다",
					content = @Content(
							schema = @Schema(implementation = ProblemActivityResponse.class),
							examples = {
									@ExampleObject(
											name = "문제 진입 직후 (turns 비어 있음)",
											value = """
													{
													  "problemNo": 1,
													  "problemTotal": 3,
													  "title": "그래프 상태 전이 설계",
													  "code": {
													    "path": "app/graph.py",
													    "language": "PYTHON",
													    "snippet": "from langgraph.graph import StateGraph\\n...",
													    "lineStart": 5,
													    "lineEnd": 8,
													    "references": [
													      { "type": "PRIMARY_BLOCK", "path": "app/graph.py", "lineStart": 5, "lineEnd": 8 },
													      { "type": "CALLER", "path": "app/main.py", "lineStart": 21, "lineEnd": 24 }
													    ]
													  },
													  "turns": [],
													  "current": {
													    "sequenceNo": 1,
													    "questionText": "이 함수가 상태를 어떤 기준으로 나눴는지 설명해 주세요.",
													    "shownHints": [],
													    "hintsUsed": 0,
													    "hintsLeft": 2,
													    "highlight": { "path": "app/graph.py", "lineStart": 5, "lineEnd": 8 },
													    "lastTurnOfSession": false
													  }
													}"""),
									@ExampleObject(
											name = "힌트를 쓰고 재도전 중",
											description = "shownHints가 차 있고 hintsLeft가 줄어든다. 같은 질문에 다시 답한다",
											value = """
													{
													  "problemNo": 1,
													  "problemTotal": 3,
													  "title": "그래프 상태 전이 설계",
													  "code": {
													    "path": "app/graph.py",
													    "language": "PYTHON",
													    "snippet": "from langgraph.graph import StateGraph\\n...",
													    "lineStart": 5,
													    "lineEnd": 8,
													    "references": []
													  },
													  "turns": [
													    {
													      "sequenceNo": 1,
													      "questionText": "이 함수가 상태를 어떤 기준으로 나눴는지 설명해 주세요.",
													      "answerText": "노드마다 다르게 했습니다.",
													      "answeredAt": "2026-08-15T04:06:40Z",
													      "highlight": { "path": "app/graph.py", "lineStart": 5, "lineEnd": 8 }
													    }
													  ],
													  "current": {
													    "sequenceNo": 1,
													    "questionText": "이 함수가 상태를 어떤 기준으로 나눴는지 설명해 주세요.",
													    "shownHints": ["어떤 값이 다음 노드로 넘어가는지 짚어 보세요."],
													    "hintsUsed": 1,
													    "hintsLeft": 1,
													    "highlight": { "path": "app/graph.py", "lineStart": 5, "lineEnd": 8 },
													    "lastTurnOfSession": false
													  }
													}"""),
									@ExampleObject(
											name = "끝난 문제 (세션 종료 후 열람)",
											description = "current 키가 없다. 세션이 끝난 뒤에는 모든 문제가 이 모양으로 열린다",
											value = """
													{
													  "problemNo": 1,
													  "problemTotal": 3,
													  "title": "그래프 상태 전이 설계",
													  "code": {
													    "path": "app/graph.py",
													    "language": "PYTHON",
													    "snippet": "from langgraph.graph import StateGraph\\n...",
													    "lineStart": 5,
													    "lineEnd": 8,
													    "references": []
													  },
													  "turns": [
													    {
													      "sequenceNo": 1,
													      "questionText": "이 함수가 상태를 어떤 기준으로 나눴는지 설명해 주세요.",
													      "answerText": "노드마다 다르게 했습니다.",
													      "answeredAt": "2026-08-15T04:06:40Z",
													      "highlight": { "path": "app/graph.py", "lineStart": 5, "lineEnd": 8 }
													    },
													    {
													      "sequenceNo": 1,
													      "questionText": "이 함수가 상태를 어떤 기준으로 나눴는지 설명해 주세요.",
													      "hintText": "어떤 값이 다음 노드로 넘어가는지 짚어 보세요.",
													      "answerText": "분기 조건에 쓰는 키만 상태에 남겼습니다.",
													      "answeredAt": "2026-08-15T04:08:02Z",
													      "highlight": { "path": "app/graph.py", "lineStart": 5, "lineEnd": 8 }
													    }
													  ]
													}""")
							})),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED · problemNo가 1~3 밖이다",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SESSION_NOT_ACCESSIBLE · PROBLEM_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "PROBLEM_ALREADY_CLOSED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@GetMapping("/{sessionId}/problems/{problemNo}")
	public ResponseEntity<ProblemActivityResponse> findProblem(
			@PathVariable UUID sessionId,
			// 상한 3은 문제 슬롯이 최대 3개라는 뜻이다(ck_assessment_problem_problem_no). 생성된 문제만
			// 1부터 세므로 실제 유효 범위는 1~problemTotal이고, 그보다 큰 번호는 PROBLEM_NOT_FOUND다.
			@Parameter(description = "문제 번호(1~problemTotal)") @PathVariable @Min(1) @Max(3) int problemNo) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.findProblem(userId, sessionId, problemNo));
	}

	@Operation(
			operationId = "submitSessionAnswer",
			summary = "답변 제출 → 채점 → 다음 질문 | ✅ 사용 가능",
			description = """
					답변을 AI에 보내 채점하고 다음 자리를 정한다.

					## 요청 (본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `answerText` | 필수 | string | 학생이 쓴 답변 원문 |

					⚠️ **어느 문제의 어느 질문인지는 싣지 않는다.** 진행 위치는 서버 커서가 정본이다 —
					클라이언트가 지목하게 두면 계단을 건너뛰거나 이미 닫힌 문제에 답을 붙이는 요청이 만들어지고,
					서버는 그것이 진짜 화면 상태인지 알 방법이 없다.

					길이 하한도 없다. 정의서 §6 — 짧은 답변은 알리되 막지 않는다("강제하면 의미 없는 글자를
					채운다"). 15자 미만 안내는 화면이 한다.

					## 요청 (헤더)

					| 헤더 | 필수 | 설명 |
					|---|---|---|
					| `X-Request-Id` | 선택 | 분산 추적 ID. AI로 그대로 넘어가 `ai_usage.trace_id`로 돌아온다 |

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `outcome` | enum | `RETRY_WITH_HINT` · `NEXT_TURN` · `NEXT_PROBLEM` · `PROBLEM_CLOSED` · `SESSION_ENDED` |
					| `nextProblemNo` | int? | 다음에 설 문제 번호. 세션이 끝났으면 `null` |
					| `next` | object? | 다음 질문. 세션이 끝났으면 `null` |
					| `hint` | object? | 3점 미만이라 **자동으로 열린** 힌트. 아니면 `null` |

					**hint** — `{ hintText, hintsUsed, hintsLeft }`

					⚠️ **`hint`가 오면 같은 질문에 다시 답하는 것이다.** 화면은 새 질문 말풍선을 쌓지 말고
					힌트를 덧붙인 뒤 같은 자리에서 답을 다시 받는다(`outcome=RETRY_WITH_HINT`).
					`POST /hints`와 **같은 UPDATE로 표시 시각까지 남긴 뒤** 내려오므로, 이 응답을 받고
					`POST /hints`를 따로 부르면 힌트를 두 개 쓰게 된다.

					`hint`가 `null`인 경우는 넷 — 통과했다 · 힌트를 다 썼다 · AI가 이 질문을 닫았다 ·
					다시 보기다. 앞의 셋은 `outcome`으로 갈린다.

					💡 **이것이 점수를 알려주지 않으면서 미달을 전하는 유일한 신호다.** 점수·통과 여부는
					응답에 없다(정의서 §7).

					## 한 질문의 수명

					```
					답변 ─3점 이상→ 통과. AI가 정한 다음 자리로 (NEXT_TURN · NEXT_PROBLEM · SESSION_ENDED)
					     └3점 미만→ 힌트 자동 공개 + 같은 질문 재도전 (RETRY_WITH_HINT)  ← 최대 2회
					                └ 힌트 2개 다 쓰고도 미달 → **다음 문제로** (PROBLEM_CLOSED)
					```

					⚠️ **마지막 줄은 축과 무관하다.** `L1`에서 힌트 2개를 쓰고 미달이어도 `L2`를 묻지 않고
					곧바로 다음 문제로 넘어간다 — 두 번 설명하고도 닿지 않았으면 같은 코드에 더 물어도
					얻을 것이 없다는 학습 정책이다. **이 판정만 백엔드가 AI 커서를 덮어쓴다**(나머지
					진행은 전부 AI가 정한다). 접힌 문제의 남은 축은 `NOT_REACHED`로 닫힌다.

					다음 문제가 없으면 `SESSION_ENDED`다.

					**next**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `problemId` | UUID | 다음 질문이 속한 문제 |
					| `axisCode` | enum | 이 질문이 서 있는 축. `L1` · `L2` · `L3` · `L4` |
					| `sequenceNo` | int | 질문 순번 |
					| `questionText` | string | 질문 원문 |
					| `hintsUsed` | int | 새 자리의 힌트 사용 수(보통 `0`) |
					| `highlight` | object? | `{ path, lineStart, lineEnd }`. 이 질문이 가리키는 코드 구간 |

					💡 **질문마다 강조 구간이 다르다.** 축이 올라가면(`L1`→`L2`) 같은 파일의 다른 블록을
					가리킨다 — 예: 질문 1은 `graph.py:5–8`, 질문 2는 `graph.py:39–41`. 화면은
					`next.highlight`로 왼쪽 패널의 강조만 옮기면 된다. 코드 원문(`code.snippet`)은 파일
					전체라 `NEXT_TURN`에서는 다시 받을 필요가 없다.

					`highlight`가 `null`인 경우는 AI가 준 `problemId`를 우리 행과 대조하지 못한 때뿐이다.
					그때는 `GET /{sessionId}/problems/{problemNo}`를 다시 불러 채운다.

					`outcome`이 화면 전환을 정한다 — `NEXT_PROBLEM`은 `다음 문제로`, `PROBLEM_CLOSED`는
					`이 문제는 여기까지 볼게요`, `SESSION_ENDED`는 종료 화면이다.
					앞의 둘은 **문제가 바뀌므로** `GET /{sessionId}/problems/{nextProblemNo}`로 새 코드 패널을
					받아야 한다. `NEXT_TURN`은 재조회 없이 `next`만으로 그린다.

					⚠️ **응답에 점수가 없다**(정의서 §7). 저장은 이미 끝났고 여기서는 흐름만 알려준다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `ANSWER_TEXT_REQUIRED` | 400 | 본문이 비었다 |
					| `SESSION_NOT_STARTED` | 409 | `POST /start`를 아직 부르지 않았다 |
					| `ASSESSMENT_WINDOW_CLOSED` | 409 | 개인 응시 창이 닫혔다. **답변이 더는 받아들여지지 않는다** |
					| `REVIEW_DUE_AT_PASSED` | 409 | 다시 보기 마감이 지났다 |
					| `SESSION_TIMEOUT` | 409 | 세션 상한(기본 60분) 초과. 답한 데까지 저장하고 세션을 닫는다 |
					| `PROBLEM_TIME_LIMIT_EXCEEDED` | 409 | 이 문제의 상한(기본 20분) 초과. **이미 다음 문제로 넘어갔다** |
					| `ANSWER_ALREADY_SUBMITTED` | 409 | 같은 자리에 이미 제출됐다(낙관적 잠금). 다시 불러오면 된다 |
					| `GRADING_FAILED` | 503 | AI 채점 실패. **같은 답을 그대로 다시 제출하면 된다** |

					### 🔴 시간 상한 두 개는 결과가 다르다

					| | 상한 | 넘기면 | 그다음 |
					|---|---|---|---|
					| 세션 | `timeLimitAt`(기본 60분) | 세션이 `INTERRUPTED`로 닫힌다 | `GET /current`가 **204** |
					| 문제 | 지금 문제 시작 + 20분 | 그 문제만 접고 **다음 문제로 커서가 옮겨진다** | `GET /current`가 새 `currentProblemNo` |

					⚠️ **둘 다 409를 받은 시점에 서버 상태는 이미 바뀌어 있다.** 이 응답에는 다음 자리가 실리지
					않으므로 화면은 `GET /current`를 다시 불러 커서를 읽는다 — 200이면 그 문제로 이동하고,
					204면 종료 화면이다. `PROBLEM_TIME_LIMIT_EXCEEDED`가 마지막 문제에서 나면 세션이 함께
					끝나므로 204가 온다.

					접힌 문제의 남은 축은 `NOT_REACHED`로 닫히고, 다음 문제의 20분은 **그 문제로 옮겨간
					시점부터** 새로 센다.

					⚠️ AI 채점에 **4.5~7.7초**가 걸린다. 클라이언트 타임아웃을 짧게 잡지 말 것.
					재전송이 안전한 이유는 서버가 자리마다 고정된 멱등키를 만들어 보내기 때문이다 —
					같은 자리 재시도는 AI가 처음 응답을 그대로 돌려주므로 LLM 비용이 두 번 나가지 않는다.

					## 🔴 성공 몸이 다섯 가지다 — `outcome`이 전부를 가른다

					| `outcome` | `next` | `nextProblemNo` | `hint` | 화면이 할 일 |
					|---|---|---|---|---|
					| `RETRY_WITH_HINT` | 키 없음 | 키 없음 | **있다** | 힌트를 덧붙이고 같은 자리에서 다시 받는다 |
					| `NEXT_TURN` | 다음 질문 | 같은 번호 | 키 없음 | 말풍선을 쌓고 강조만 옮긴다. 재조회 불필요 |
					| `NEXT_PROBLEM` | 다음 문제 첫 질문 | 다음 번호 | 키 없음 | `문제 조회`를 다시 불러 코드 패널을 바꾼다 |
					| `PROBLEM_CLOSED` | 다음 문제 첫 질문 | 다음 번호 | 키 없음 | `이 문제는 여기까지 볼게요` 후 위와 같다 |
					| `SESSION_ENDED` | 키 없음 | 키 없음 | 키 없음 | 종료 화면 |

					값이 없는 필드는 `null`이 아니라 **키가 빠진다**(`NON_NULL` 직렬화).""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = """
							채점 완료. **점수·통과 여부는 없다**(정의서 §7) — `outcome`이 다음 화면을 정한다""",
					content = @Content(
							schema = @Schema(implementation = AnswerSubmitResponse.class),
							examples = {
									@ExampleObject(
											name = "RETRY_WITH_HINT (3점 미만 · 힌트 자동 공개)",
											description = "같은 질문에 다시 답한다. POST /hints를 또 부르면 힌트를 두 개 쓴다",
											value = """
													{
													  "outcome": "RETRY_WITH_HINT",
													  "hint": {
													    "hintText": "어떤 값이 다음 노드로 넘어가는지 짚어 보세요.",
													    "hintsUsed": 1,
													    "hintsLeft": 1
													  }
													}"""),
									@ExampleObject(
											name = "NEXT_TURN (같은 문제의 다음 축)",
											description = "코드 원문은 그대로다. highlight만 옮기면 된다",
											value = """
													{
													  "outcome": "NEXT_TURN",
													  "nextProblemNo": 1,
													  "next": {
													    "problemId": "11111111-1111-1111-1111-111111111111",
													    "axisCode": "L2",
													    "sequenceNo": 2,
													    "questionText": "그 기준이 깨지는 입력은 어떤 것일까요?",
													    "hintsUsed": 0,
													    "highlight": { "path": "app/graph.py", "lineStart": 39, "lineEnd": 41 }
													  }
													}"""),
									@ExampleObject(
											name = "NEXT_PROBLEM (통과해서 다음 문제로)",
											value = """
													{
													  "outcome": "NEXT_PROBLEM",
													  "nextProblemNo": 2,
													  "next": {
													    "problemId": "22222222-2222-2222-2222-222222222222",
													    "axisCode": "L1",
													    "sequenceNo": 1,
													    "questionText": "이 리트리버가 문서를 고르는 기준을 설명해 주세요.",
													    "hintsUsed": 0,
													    "highlight": { "path": "app/retriever.py", "lineStart": 12, "lineEnd": 20 }
													  }
													}"""),
									@ExampleObject(
											name = "PROBLEM_CLOSED (힌트 2개 쓰고도 미달)",
											description = "남은 축(L2~L4)을 묻지 않고 곧바로 다음 문제로 간다. 이 판정만 백엔드가 AI 커서를 덮어쓴다",
											value = """
													{
													  "outcome": "PROBLEM_CLOSED",
													  "nextProblemNo": 2,
													  "next": {
													    "problemId": "22222222-2222-2222-2222-222222222222",
													    "axisCode": "L1",
													    "sequenceNo": 1,
													    "questionText": "이 리트리버가 문서를 고르는 기준을 설명해 주세요.",
													    "hintsUsed": 0,
													    "highlight": { "path": "app/retriever.py", "lineStart": 12, "lineEnd": 20 }
													  }
													}"""),
									@ExampleObject(
											name = "SESSION_ENDED (마지막 답변)",
											description = "outcome 외에는 키가 없다. 응시 완료 신호는 GET /assessment-rounds의 ASSESSMENT_COMPLETED로도 확인된다",
											value = """
													{
													  "outcome": "SESSION_ENDED"
													}"""),
									@ExampleObject(
											name = "highlight 없음 (AI problemId 대조 실패)",
											description = "GET /{sessionId}/problems/{nextProblemNo}를 다시 불러 채운다",
											value = """
													{
													  "outcome": "NEXT_TURN",
													  "next": {
													    "problemId": "11111111-1111-1111-1111-111111111111",
													    "axisCode": "L2",
													    "sequenceNo": 2,
													    "questionText": "그 기준이 깨지는 입력은 어떤 것일까요?",
													    "hintsUsed": 0
													  }
													}""")
							})),
			@ApiResponse(responseCode = "400",
					description = "ANSWER_TEXT_REQUIRED · VALIDATION_FAILED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SESSION_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "SESSION_NOT_STARTED · ASSESSMENT_WINDOW_CLOSED · REVIEW_DUE_AT_PASSED"
							+ " · SESSION_TIMEOUT · ANSWER_ALREADY_SUBMITTED · PROBLEM_TIME_LIMIT_EXCEEDED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503",
					description = "GRADING_FAILED — **같은 답을 그대로 다시 제출하면 된다**",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping(value = "/{sessionId}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<AnswerSubmitResponse> submitAnswer(
			@PathVariable UUID sessionId,
			@Valid @RequestBody AnswerSubmitRequest request,
			@RequestHeader(value = TRACE_ID_HEADER, required = false) String traceId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.submitAnswer(userId, sessionId, request, traceId));
	}

	@Operation(
			operationId = "openSessionHint",
			summary = "다시 설명(힌트) 요청 | ✅ 사용 가능",
			description = """
					`다시 설명해 주세요`를 눌렀을 때 부른다. **AI를 부르지 않는다** — 힌트 문구는 코드 분석 시점에
					이미 동결돼 DB에 있고 세션은 꺼내 보여줄 뿐이다("힌트는 재진술만 — 질문을 다르게 말할 뿐
					코드 위치·선택지·답의 방향을 주지 않는다"). 즉답이다.

					💡 **힌트가 열리는 경로는 둘이다.** 답변이 3점 미만이면 `POST /answers`의 응답에
					`hint`로 **자동으로** 열려 내려오고, 학생이 원할 때는 이 경로로 **직접** 연다.
					둘은 같은 횟수(질문당 2회)를 나눠 쓰며 같은 UPDATE를 탄다.

					답변란이 비어 있어도 부를 수 있다 — 질문을 이해하지 못했을 때 미리 보는 용도다.
					반대로 **끝난 질문에는 열리지 않는다**(통과했거나, 마지막 힌트까지 쓰고 미달이라
					`NOT_PASSED`로 닫혔다).

					⚠️ `POST /answers`가 `hint`를 함께 준 뒤에 이 경로를 또 부르면 **두 번째 힌트가 열린다.**
					자동으로 받은 힌트는 이미 소진된 것이므로 화면은 그것을 그리기만 하고 다시 부르지 않는다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 세션 식별자 |

					본문은 없다. 어느 질문의 힌트인지는 서버 커서가 정한다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `problemId` | UUID | 이 힌트가 속한 문제 |
					| `axisCode` | enum | `L1` · `L2` · `L3` · `L4` |
					| `hintText` | string | 힌트 문구. 분석 시점에 동결된 것을 그대로 준다 |
					| `hintsUsed` | int | 지금까지 쓴 힌트 수(1~2) |
					| `hintsLeft` | int | 남은 횟수. `0`이면 화면은 버튼을 문구로 바꾼다 |

					⚠️ **써도 점수가 깎이지 않는다.** 남은 횟수를 내려보내는 것은 화면이 `2번 남음`을 그리기
					위해서이지 불이익을 알리기 위해서가 아니다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `HINT_EXHAUSTED` | 409 | 단계당 2회를 다 썼다 |
					| `HINT_NOT_AVAILABLE` | 409 | 이미 끝난 질문이다(통과했거나 NOT_PASSED로 닫혔다) |
					| `SESSION_NOT_STARTED` | 409 | `POST /start`를 아직 부르지 않았다 |
					| `ASSESSMENT_WINDOW_CLOSED` | 409 | 개인 응시 창이 닫혔다 |

					다시 보기에서 막는 근거는 정의서 §6+다 — "이번에는 다시 설명해 드리지 않아요. 지난번과 같은
					질문이라 이미 한 번 들었어요."

					AI를 안 부르는데도 서버를 타는 이유는 **표시 시각을 남기기 위해서다.** 그 기록이 없으면
					힌트를 열어 둔 채 새로고침했을 때 사용 횟수가 0으로 되돌아가 학생이 힌트를 세 번, 네 번 쓴다.

					## 성공 몸이 두 가지다

					`hintsLeft`가 `1`이냐 `0`이냐로 갈린다. `0`이면 화면은 버튼을 눌리지 않는 문구로 바꾼다 —
					그대로 두면 다음 클릭이 `409 HINT_EXHAUSTED`로 떨어진다.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "힌트 문구. **점수는 깎이지 않는다** — 남은 횟수는 화면 표시용이다",
					content = @Content(
							schema = @Schema(implementation = HintResponse.class),
							examples = {
									@ExampleObject(
											name = "첫 번째 힌트 (1번 남음)",
											value = """
													{
													  "problemId": "11111111-1111-1111-1111-111111111111",
													  "axisCode": "L1",
													  "hintText": "어떤 값이 다음 노드로 넘어가는지 짚어 보세요.",
													  "hintsUsed": 1,
													  "hintsLeft": 1
													}"""),
									@ExampleObject(
											name = "마지막 힌트 (0번 남음)",
											description = "화면은 버튼을 문구로 바꾼다. 또 부르면 409 HINT_EXHAUSTED다",
											value = """
													{
													  "problemId": "11111111-1111-1111-1111-111111111111",
													  "axisCode": "L1",
													  "hintText": "노드 사이에 공유되는 키가 무엇인지 세어 보세요.",
													  "hintsUsed": 2,
													  "hintsLeft": 0
													}""")
							})),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SESSION_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "HINT_EXHAUSTED · HINT_NOT_AVAILABLE · SESSION_NOT_STARTED · SESSION_TIMEOUT",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping("/{sessionId}/hints")
	public ResponseEntity<HintResponse> openHint(@PathVariable UUID sessionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.openHint(userId, sessionId));
	}

	@Operation(
			operationId = "recordSessionActivity",
			summary = "응시 중 관찰 신호 기록 | ✅ 사용 가능",
			description = """
					창 이탈·연결 끊김·첫 타이핑 지연을 남긴다. **AI를 부르지 않고 진행 상태도 바꾸지 않는다** —
					오직 기록이며 응답 본문이 없다(`204`).

					## 요청

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 경로 파라미터 |

					**본문** — 셋 다 선택이되 **최소 하나**는 있어야 한다. 여러 개를 함께 보내도 된다.

					| 필드 | 타입 | 범위 | 설명 |
					|---|---|---|---|
					| `awaySeconds` | int? | `0`~`86400` | 창을 떠나 있던 시간(초) |
					| `disconnectedSeconds` | int? | `0`~`86400` | 연결이 끊겼던 시간(초) |
					| `firstKeystrokeDelayMs` | int? | `0`~`86400000` | 질문이 보인 뒤 첫 글자까지(ms) |

					어느 문제의 어느 질문에 붙는지는 **싣지 않는다** — 답변 제출과 같은 이유로 진행 위치는
					서버 커서가 정본이다.

					## 언제 부르나

					| 화면 이벤트 | 보낼 값 |
					|---|---|
					| `visibilitychange`로 돌아옴 · `focus` | `awaySeconds` — **복귀 시점에 한 번만** |
					| 소켓/요청 재연결 성공 | `disconnectedSeconds` — 재연결 시점에 한 번만 |
					| 답변 입력창의 첫 키 입력 | `firstKeystrokeDelayMs` |

					⚠️ `awaySeconds`·`disconnectedSeconds`는 **보낼 때마다 횟수가 1씩 올라간다.** 이탈 중에
					주기적으로 보내면 한 번 나간 것이 열 번으로 기록되어 무효 응시 판정
					(`EXCESSIVE_WINDOW_LEAVE`·`EXCESSIVE_CONNECTION_LOSS`)이 틀린다. 반면
					`firstKeystrokeDelayMs`는 슬롯당 첫 값만 남으므로 중복 전송이 안전하다.

					## 응답

					`204 No Content`. 본문이 없다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `ACTIVITY_SIGNAL_REQUIRED` | 400 | 세 값이 모두 비었다 |
					| `SESSION_NOT_ACCESSIBLE` | 404 | 없거나 남의 세션 |
					| `SESSION_NOT_STARTED` | 409 | `POST /start`를 아직 부르지 않았다 |
					| `SESSION_TIMEOUT` | 409 | 시간 상한 초과. 그 자리에서 세션을 닫는다 |
					| `SESSION_ALREADY_ENDED` | 409 | 이미 끝난 세션 |

					💡 **끝난 세션의 신호는 버린다.** 세션을 닫은 뒤 도착한 복귀 비콘까지 받아 주면 종료
					시각 이후의 이탈이 합계에 섞인다. 화면은 `409`를 무시하면 된다 — 재전송할 값이 아니다.

					이 경로가 없으면 `window_leave_count`·`connection_loss_count`·`*_away_count`·
					`*_first_keystroke_delay_ms`가 전부 초기값으로 남고, 무효 응시 판정과 매니저 브리프의
					"어느 답변이 의심스러운가"가 빈 값으로 돌아간다.

					## 성공 몸은 한 가지뿐이다

					**성공은 언제나 `204`이고 본문이 없다.** 세 값 중 하나만 보내든 셋을 함께 보내든 응답은 같다 —
					무엇이 기록됐는지 돌려주지 않는다. 화면이 그 값을 다시 그릴 일이 없고, 돌려주면 클라이언트가
					서버 누적치를 자기 상태로 삼게 되기 때문이다.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204",
					description = "기록됨. **본문이 없다** — 진행 상태는 바뀌지 않는다",
					content = @Content),
			@ApiResponse(responseCode = "400",
					description = "ACTIVITY_SIGNAL_REQUIRED · VALIDATION_FAILED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SESSION_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = """
							SESSION_NOT_STARTED · SESSION_TIMEOUT · SESSION_ALREADY_ENDED — \
							**끝난 세션의 409는 무시하면 된다.** 재전송할 값이 아니다""",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping(value = "/{sessionId}/activity", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> recordActivity(
			@PathVariable UUID sessionId,
			@Valid @RequestBody SessionActivityRequest request) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		sessionService.recordActivity(userId, sessionId, request);
		return ResponseEntity.noContent().build();
	}

	@Operation(
			operationId = "recordSessionActivityEvent",
			summary = "관찰 신호 이벤트 1건 기록 | ✅ 사용 가능",
			description = """
					창 이탈·연결 끊김·첫 타이핑 지연을 **발생 건 하나씩** 남긴다. `POST /activity`와 달리
					**발생 시작 시각을 클라이언트가 직접 싣는다** — 서버가 지속 시간으로 거꾸로 근사하지 않는다.
					카운터(문제·세션 누적)는 `POST /activity`와 같은 값을 같은 방식으로 올린다.

					## 요청

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 경로 파라미터 |

					**본문** — 셋 다 필수, 이벤트 1건.

					| 필드 | 타입 | 범위 | 설명 |
					|---|---|---|---|
					| `eventType` | string | `WINDOW_LEAVE`·`CONNECTION_LOSS`·`FIRST_KEYSTROKE_DELAY` | 이벤트 종류 |
					| `occurredAt` | Instant | | 이 이벤트가 시작된 시각(클라이언트 실측) |
					| `durationMs` | int | `0`~`86400000` | 지속 시간(ms) |

					어느 문제의 어느 질문에 붙는지는 **싣지 않는다** — `POST /activity`와 같은 이유로 진행
					위치는 서버 커서가 정본이다.

					⚠️ `WINDOW_LEAVE`·`CONNECTION_LOSS`는 **부를 때마다 횟수가 1씩 올라간다.** `FIRST_KEYSTROKE_DELAY`는
					슬롯당 첫 값만 남으므로 중복 전송이 안전하다.

					## 응답

					`204 No Content`. 본문이 없다.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204",
					description = "기록됨. **본문이 없다** — 진행 상태는 바뀌지 않는다",
					content = @Content),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SESSION_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = """
							SESSION_NOT_STARTED · SESSION_TIMEOUT · SESSION_ALREADY_ENDED — \
							**끝난 세션의 409는 무시하면 된다.** 재전송할 값이 아니다""",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping(value = "/{sessionId}/activity-events", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> recordActivityEvent(
			@PathVariable UUID sessionId,
			@Valid @RequestBody SessionActivityEventRequest request) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		sessionService.recordActivityEvent(userId, sessionId, request);
		return ResponseEntity.noContent().build();
	}
}
