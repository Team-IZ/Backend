package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.reporting.application.ReportBatchService;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 세션 1건의 리포트를 <b>조건 없이</b> 만드는 연동 시험용 엔드포인트.
 *
 * <h2>왜 별도 컨트롤러인가</h2>
 *
 * <p>{@link ReportController}에 메서드로 얹지 않고 클래스를 나눈 이유는
 * {@code @ConditionalOnProperty}가 <b>빈 단위로만 걸리기</b> 때문이다. 조회 API와 한 클래스에 두면
 * 이 위험한 경로 하나 때문에 리포트 조회 전체를 끄고 켜게 된다. {@code ReportJobScheduler}가
 * 스케줄러만 따로 떼어 둔 것과 같은 판단이다.
 *
 * <h2>🔴 기본값이 꺼짐인 이유</h2>
 *
 * <p>이 경로는 <b>세션 유효성을 하나도 보지 않는다</b>(§{@code forceGenerateSession}). 켜져 있으면
 * 아무 세션 ID나 밀어 넣어 LLM 비용을 태울 수 있고, 그 비용은 회수되지 않는다 —
 * 무효 세션에도 요청이 나가지만 결과는 발행되지 않기 때문이다.
 *
 * <p>연동 시험이 끝나면 다시 끈다. 운영에서 정말 필요한 것은 조건을 지키는
 * {@link ReportBatchService#regenerateSession}이고, 그쪽은 화면이 생길 때 별도로 붙인다.
 */
@Tag(name = "Reporting", description = "리포트 발행 이력·본문·문답 조회 API (v2 IA: TR-04 / OP-05)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "ai.report.force-endpoint.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ReportForceGenerationController {

	private final ReportBatchService reportBatchService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(operationId = "forceGenerateReport",
			summary = "[연동 시험] 세션 지정 리포트 강제 생성 | ⚠️ 조건 없음",
			description = """
					세션 ID 하나로 **저장된 검증세션 결과를 읽어 8필드를 조립하고 FastAPI에 보냅니다.**
					문제 1건당 요청 1회이므로 문제가 2개면 LLM 호출도 2회입니다.

					### 아무 조건도 보지 않습니다

					배치(`findDueProblems`)나 재생성(`findTargetBySession`)과 달리 **전부 뺐습니다.**

					| 조건 | 배치 | 재생성 | 이 API |
					|---|---|---|---|
					| 세션·응시 `COMPLETED` | ✅ | ✅ | **❌** |
					| 종료 사유 6종 제외 | ✅ | ✅ | **❌** |
					| 무효 확인 | ✅ | ✅ | **❌** |
					| 단계 정리 완료 | ✅ | ✅ | **❌** |
					| 발행 예정 시각 도래 | ✅ | ✅ | **❌** |
					| 횟수 상한·중복 차단 | ✅ | ❌ | **❌** |

					세션 ID만 맞으면 나갑니다. 회차 마감을 기다리거나 `assessment_due_at`을 손대지 않고
					AI 계약과 8필드 조립을 확인하려고 둔 경로입니다.

					### 그래도 발행은 막힙니다

					여기서 만든 리포트가 학생에게 나가지는 않습니다. `ReportRunFinalizer`가 확정 직전에
					세션 유효성과 발행 예정 시각을 **다시** 보고, 어긋나면 스냅샷만 만들고
					`published_at`을 비워 둡니다. **생성 게이트만 풀리고 발행 게이트는 그대로입니다.**

					### 202이고 결과는 폴링이 회수합니다

					AI가 202 + jobId만 주므로 이 응답도 즉시 돌아옵니다. 상태는
					`report_generation_item`에서 확인하세요 — `QUEUED` → `RUNNING` → `SUCCEEDED`.
					**1건당 실측 129초**이고 폴링 주기가 1분이라 반영까지 최대 1분 더 걸립니다.

					> 이 엔드포인트는 `ai.report.force-endpoint.enabled=true`일 때만 등록됩니다.
					> 꺼져 있으면 404입니다.
					""")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "요청 접수. `generationRunId`로 추적"),
			@ApiResponse(responseCode = "404", description = "REPORT_SESSION_NOT_FOUND 그 세션이 없음"),
			@ApiResponse(responseCode = "409", description = """
					REPORT_SESSION_HAS_NO_PROBLEM 채점된 문제가 없는 세션
					· REPORT_GENERATION_ALREADY_RUNNING 이미 진행 중인 수동 실행이 있음"""),
			@ApiResponse(responseCode = "500", description = "REPORT_MODEL_NOT_CONFIGURED 모델 설정 어긋남"),
			@ApiResponse(responseCode = "403", description = "매니저 권한이 없음")
	})
	@PreAuthorize("hasRole('MANAGER')")
	@PostMapping("/sessions/{sessionId}/generation")
	public ResponseEntity<ForceGenerationResponse> forceGenerate(
			@Parameter(description = "리포트를 만들 검증세션 ID", required = true)
			@PathVariable UUID sessionId
	) {
		UUID runId = reportBatchService.forceGenerateSession(
				sessionId, currentUserResolver.resolveCurrentMemberId().toString());

		return ResponseEntity.accepted().body(new ForceGenerationResponse(sessionId, runId));
	}

	@Schema(description = "강제 생성 접수 결과")
	public record ForceGenerationResponse(

			@Schema(description = "요청한 세션 ID")
			UUID sessionId,

			@Schema(description = """
					만들어진 실행 ID(`report_generation_run.generation_run_id`).
					`report_generation_item.generation_run_id`로 진행 상황을 조회할 수 있다.
					""")
			UUID generationRunId) {
	}
}
