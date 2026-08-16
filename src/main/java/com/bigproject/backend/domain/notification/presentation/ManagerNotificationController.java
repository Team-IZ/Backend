package com.bigproject.backend.domain.notification.presentation;

import com.bigproject.backend.domain.notification.application.ManagerNotificationService;
import com.bigproject.backend.domain.notification.presentation.dto.NotificationInboxResponse;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderRequest;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Tag(name = "Notification", description = "매니저 인박스와 독촉 발송")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class ManagerNotificationController {
	private final ManagerNotificationService service;

	@Operation(
			operationId = "findManagerNotificationInbox",
			summary = "매니저 인박스 조회 | ✅ 사용 가능",
			description = """
					매니저 대시보드 인박스를 조회한다. 제출 누락·분석 실패·응시 미시작 같은 **조치가 필요한
					항목**과, 무효 응시 검토·면담 대기·독촉 발송 이력을 **한 목록**으로 합쳐 마감 임박 순으로
					보여준다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `cohortId` | **필수** | UUID | 조회할 기수 |

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `projectId` | 선택 | UUID | 프로젝트로 좁힌다. 생략하면 담당 반 전체 |
					| `assessmentRoundId` | 선택 | UUID | 회차로 좁힌다 |
					| `since` | 선택 | datetime (ISO 8601) | 이 시각 이후 발생한 항목만. 생략하면 기간 제한 없음 |
					| `includeResolved` | 선택 | boolean | `true`면 이미 해소된 항목도 포함. 기본 `false`(미해소만) |
					| `cursor` | 선택 | string | 다음 페이지 커서. 이전 응답의 `nextCursor`를 그대로 넣는다 |
					| `size` | 선택 | int | 페이지 크기. 1~100, 기본 20 |

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `items[]` | array | 인박스 항목 목록. 마감 임박 순(마감 없는 항목은 뒤로) |
					| `nextCursor` | string? | 다음 페이지 커서. 더 없으면 `null` |

					### items[] 각 항목

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `itemId` | string | 항목 식별자. 원천에 따라 형식이 다르다(`ATTENDANCE:...`·`INVALID:...`·`INTERVIEW:...`·`REMINDER:...`) |
					| `itemType` | string | 항목 유형. `SUBMISSION_MISSING`·`ANALYSIS_FAILED`·`ASSESSMENT_NOT_STARTED`·`REVIEW`·`ASSESSMENT`·`INVALID_ATTEMPT`·`INTERVIEW`·`REMINDER` |
					| `projectId` / `assessmentRoundId` / `classroomId` / `teamId` / `traineeId` | UUID | 이 항목이 걸린 대상 |
					| `subject` | string | 대상 이름(교육생명 또는 팀명) |
					| `sourceStatus` | string | 원천의 현재 상태 |
					| `reasonCode` | string? | 사유 코드. 원천마다 의미가 다르다 |
					| `evidence` | string | 화면에 그대로 보여줄 근거 요약 문구 |
					| `deadlineAt` | timestamp? | 정렬 기준 마감 시각. 없으면 `null`(정렬에서 뒤로 밀림) |
					| `occurredAt` | timestamp | 발생/갱신 시각 |
					| `resolved` | boolean | 이미 해소됐는지. `includeResolved=false`면 이 값이 `true`인 항목은 안 옴 |
					| `reminderEligible` | boolean | 지금 독촉 발송 대상이 될 수 있는지. `true`인 항목만 `POST /reminders`로 보낼 수 있다 |

					## 정렬 규칙

					**마감이 있는 항목이 먼저**, 그 안에서 마감이 이른 순이다. 마감이 없는 항목(면담 등)은
					뒤로 밀리고 그 안에서는 최근 발생 순이다.

					## 네 가지 원천을 하나로 합친다

					`assessment_round_attendance`(제출·분석·응시) · `manager_invalid_attempt_review_view`(무효
					응시 검토) · `manager_interview_list_view`(면담 대기) · `reminder_dispatch`(독촉 이력)
					네 원천을 `UNION ALL`로 합쳐 하나의 인박스로 낸다. 담당 반(`manager_assignment`) 기준으로
					필터링되므로 다른 매니저의 반은 보이지 않는다.

					## 커서 페이지네이션

					`cursor`는 이전 페이지 마지막 항목의 `itemId`를 인코딩한 값이다. 직접 만들지 말고
					`nextCursor`를 그대로 다음 요청에 넣어야 한다 — 잘못된 커서를 보내면 400이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "인박스 조회 성공"),
			@ApiResponse(responseCode = "400", description = "INBOX_CURSOR_INVALID 커서 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "MANAGER_SCOPE_NOT_FOUND 담당 범위 밖의 기수이거나 존재하지 않음"),
	})
	@GetMapping("/inbox")
	public ResponseEntity<NotificationInboxResponse> findInbox(
			@Parameter(description = "조회할 기수 ID") @PathVariable UUID cohortId,
			@Parameter(description = "프로젝트로 좁힌다") @RequestParam(required = false) UUID projectId,
			@Parameter(description = "회차로 좁힌다") @RequestParam(required = false) UUID assessmentRoundId,
			@Parameter(description = "이 시각 이후 발생한 항목만 조회한다")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
			@Parameter(description = "true면 이미 해소된 항목도 포함한다") @RequestParam(defaultValue = "false") boolean includeResolved,
			@Parameter(description = "다음 페이지 커서. 이전 응답의 nextCursor를 그대로 넣는다") @RequestParam(required = false) String cursor,
			@Parameter(description = "페이지 크기(1~100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Authentication authentication) {
		return ResponseEntity.ok(service.findInbox(authentication.getName(), cohortId, projectId,
				assessmentRoundId, since, includeResolved, cursor, size));
	}

	@Operation(
			operationId = "sendManagerReminder",
			summary = "매니저 단건 독촉 발송 | ⚠️ 사용 불가",
			description = """
                ⚠️ **이 기능은 폐기되었습니다.** 응시 독촉 기능 자체가 제품에서 제거되기로
                결정되어 프론트엔드에서 이 API를 호출하면 안 됩니다.

                로직은 당분간 코드에 남아있지만 곧 완전히 제거될 예정입니다. 신규 연동을
                추가하지 마세요.
                """
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "독촉 발송 성공(또는 같은 멱등키로 이전 결과 재반환)"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락 · REMINDER_TARGET_INVALID 팀·교육생 중 정확히 하나가 아님 · REMINDER_REASON_INVALID 사유와 대상 종류가 안 맞음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "409", description = "REMINDER_TARGET_NOT_ELIGIBLE 지금 상태에서 독촉할 수 없음 · IDEMPOTENCY_KEY_REUSED 같은 멱등키가 다른 요청에 사용됨"),
	})
	@PostMapping(value = "/reminders", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SendReminderResponse> sendReminder(
			@Parameter(description = "대상 기수 ID") @PathVariable UUID cohortId,
			@Parameter(description = "요청 고유 키. 같은 키로 재요청하면 중복 발송 대신 이전 결과를 반환한다")
			@RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
			@Valid @RequestBody SendReminderRequest request,
			Authentication authentication) {
		return ResponseEntity.ok(service.sendReminder(authentication.getName(), cohortId, idempotencyKey, request));
	}
}