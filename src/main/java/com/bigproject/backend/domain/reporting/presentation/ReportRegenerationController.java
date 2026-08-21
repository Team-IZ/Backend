package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUser;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 배치가 놓친 세션의 리포트를 운영자 판단으로 다시 만드는 엔드포인트.
 *
 * <h2>{@link ReportForceGenerationController}와 다른 점</h2>
 *
 * <p>그쪽은 세션 유효성을 하나도 안 보는 연동 시험 전용이라 기본 꺼짐이다. 이쪽은
 * {@link ReportBatchService#regenerateSession}을 그대로 부르는데, 그 메서드가 배치와 동일한 6종
 * 검증(세션·응시 완료, 종료 사유 6종 제외, 무효 확인, 발행 예정 시각, 단계 정리 완료)을 이미 다 거치고
 * 중복 실행도 DB 유니크 제약으로 막는다 — 그래서 별도 플래그로 잠그지 않고 상시 등록한다.
 *
 * <h2>D1: 목록 조회 API를 안 만들었다</h2>
 *
 * <p>WHY — {@code regenerateSession}의 재생성 대상은 배치가 놓친 것들(상한 소진·PARTIAL로 닫힌
 * run)이라 발생 빈도가 낮고, 운영자가 이미 모니터링 쿼리로 sessionId를 확보한 뒤 이 API를 부르는
 * 흐름을 전제한다({@code ReportBatchService#regenerateSession} 참고).
 * <br>COST — sessionId를 모르면 이 API를 못 쓴다. 조회 SQL을 매번 운영자가 직접 돌려야 한다.
 * <br>EXIT — 빈도가 늘어나면 그 모니터링 쿼리를 GET 엔드포인트로 옮기면 된다. 이 컨트롤러는
 * 안 건드려도 된다.
 */
@Tag(name = "Reporting", description = "리포트 발행 이력·본문·문답 조회 API (v2 IA: TR-04 / OP-05)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReportRegenerationController {

	private final ReportBatchService reportBatchService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(operationId = "regenerateReport",
			summary = "세션 지정 리포트 재생성 | ✅ 사용 가능",
			description = """
					배치가 다시 집지 못하는 세션의 리포트를 운영자 판단으로 다시 만듭니다.

					## 언제 쓰는가

					배치(`dispatchDueSessions`)는 두 가지 이유로 대상을 영구히 놓칠 수 있습니다.

					- `ai.report.max-attempts`를 소진했다
					- 문제 1개만 실패해 run이 `PARTIAL`로 닫혔다(`BLOCKING_RUN_EXISTS`가 `FAILED`만
					  다시 집어서, 상한 소진보다 흔하게 발생합니다)

					둘 다 배치 스스로는 복구하지 못하고, 이 API가 유일한 복구 경로입니다.

					## `ReportForceGenerationController`와 다릅니다

					이 API는 세션·응시 완료, 종료 사유 6종 제외, 무효 확인, 발행 예정 시각, 단계 정리
					완료를 배치와 동일하게 전부 검증합니다. 조건이 안 맞으면 만들지 않습니다 —
					연동 시험 전용 강제 생성 경로와 혼동하지 마세요.

					## 재실행 횟수 상한이 없습니다

					`USER_REQUESTED` 실행은 재시도 상한 카운터에 안 잡힙니다. 몇 번이든 다시 부를 수
					있다는 뜻이라, 같은 대상을 반복해서 누르고 있다면 그건 이 도구가 아니라 AI 쪽
					문제일 가능성이 높습니다.

					## 202이고 결과는 폴링이 회수합니다

					즉시 `generationRunId`만 돌아옵니다. 진행 상황은 `report_generation_item`에서
					확인하세요.

					## 오류

					| 코드 | 상태 | 뜻 |
					|---|---|---|
					| `REPORT_REGENERATION_TARGET_NOT_ELIGIBLE` | 404 | 세션이 없거나, 호출한 매니저가 지금 담당하지 않는 교육생이거나, 위 5종 조건에 안 맞습니다. 어느 쪽인지는 이 API로 구분되지 않습니다(존재 자체를 알려주지 않기 위해서입니다) |
					| `REPORT_SESSION_HAS_NO_PROBLEM` | 409 | 채점된 문제가 없는 세션입니다 |
					| `REPORT_GENERATION_ALREADY_RUNNING` | 409 | 이미 진행 중인 수동 실행이 있습니다. 그 실행이 끝나기를 기다리세요 |
					| `REPORT_MODEL_NOT_CONFIGURED` | 500 | 리포트 생성 모델 설정이 어긋났습니다. 요청 문제가 아니라 운영 설정 문제입니다 |
					""")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "요청 접수. `generationRunId`로 추적"),
			@ApiResponse(responseCode = "403", description = "매니저 권한이 없음")
	})
	@PreAuthorize("hasRole('MANAGER')")
	@PostMapping("/sessions/{sessionId}/regeneration")
	public ResponseEntity<RegenerationResponse> regenerate(
			@Parameter(description = "리포트를 다시 만들 검증세션 ID", required = true)
			@PathVariable UUID sessionId
	) {
		AuthUser manager = currentUserResolver.resolveCurrentUser();
		UUID runId = reportBatchService.regenerateSession(
				sessionId, manager.userId(), manager.organizationId(), manager.userId().toString());

		return ResponseEntity.accepted().body(new RegenerationResponse(sessionId, runId));
	}

	@Schema(description = "재생성 접수 결과")
	public record RegenerationResponse(

			@Schema(description = "요청한 세션 ID")
			UUID sessionId,

			@Schema(description = """
					만들어진 실행 ID(`report_generation_run.generation_run_id`).
					`report_generation_item.generation_run_id`로 진행 상황을 조회할 수 있다.
					""")
			UUID generationRunId) {
	}
}
