package com.bigproject.backend.domain.evaluation.presentation;

import com.bigproject.backend.domain.evaluation.application.EvaluationService;
import com.bigproject.backend.domain.evaluation.presentation.dto.ProjectEvaluationSummaryResponse;
import com.bigproject.backend.domain.evaluation.presentation.dto.TraineeEvaluationDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 매니저가 보는 채점 결과 조회 — MG-08 프로젝트 상세 '결과' 탭.
 *
 * <p>화면이 마스터-디테일이라 <b>엔드포인트를 둘로 나눴다.</b> 왼쪽 목록은 사람마다 '막힘 N'만
 * 필요한데 오른쪽 상세는 개념 × 축 4단계 × 채점 근거 텍스트라 부피가 한 자릿수 배 차이난다.
 * 한 응답에 합치면 클릭하지도 않은 사람의 근거 문장까지 매번 실려 나간다.
 */
@Tag(name = "Evaluation", description = "채점 결과 조회 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('MANAGER')")
@Validated
@RestController
@RequiredArgsConstructor
public class ManagerEvaluationController {

	private final EvaluationService evaluationService;

	@Operation(
			operationId = "findProjectEvaluationSummary",
			summary = "[프로젝트 상세 - 결과 탭] 프로젝트 회차 결과 종합 조회 (매니저) | ✅ 사용 가능",
			description = """
					결과 탭의 **왼쪽 교육생 목록과 '프로젝트 종합' 화면**을 그린다.

					## 요청

					| 파라미터 | 필수 | 설명 |
					|---|---|---|
					| `projectId` (경로) | 필수 | 조회할 프로젝트 |
					| `roundNo` | 선택 | 회차 번호(기본 1) |
					| `classId` | 선택 | 담당 반 하나로 좁힌다. 생략하면 **담당 반 전체**다 |

					🔴 **응답은 호출자가 담당하는 반으로 제한된다.** 담당하지 않는 반의 `classId`를 지정하면
					`404 MANAGER_SCOPE_NOT_FOUND`다.

					## 응답 (200)

					| 필드 | 설명 |
					|---|---|
					| `reportPublished` | 회차 리포트 발행 여부. 발행 방식이 ROUND_BATCH라 회차 단위 판정 |
					| `resultAvailable` | 결과를 그릴 수 있는지. false면 화면은 빈 상태를 보여준다 |
					| `summary` | `totalCount` · `attendedCount` · `failedCount` · `notAttendedCount`(세션을 못 한 인원 = 미응시 + 미제출 + 분석 실패) · `invalidCount` |
					| `classWarnings[]` | 집단 미달 경고. 유효 응시자의 **절반을 넘는** 인원이 막힌 개념 |
					| `conceptAggregates[]` | 개념별 막힌 사람 · 코드에 없던 사람과 명단 |
					| `trainees[]` | 교육생 목록. 개념별 도달 결과까지 담고 **축별 단계는 담지 않는다** |

					🆕 **`resultStatus = NOT_ATTENDED`가 팀 미제출·분석 실패까지 포함한다.** 종전에는 그 둘이
					값이 없어 `IN_PROGRESS`로 나갔고, 그래서 **이미 종료된 회차에 「응시 중」인 사람이
					남았다.** 셋 다 검증 세션을 하지 못했다는 같은 사실이라 한 값으로 묶고,
					**원인은 `trainees[].notAttendedReason`으로 가른다**(`NO_SHOW` · `NOT_SUBMITTED` ·
					`ANALYSIS_FAILED`). `summary.notAttendedCount`도 이 셋의 합이며 반별 현황
					(`class-progress`)의 같은 이름 필드와 기준이 일치한다.

					🔴 **독촉·면담 대상은 `NO_SHOW`뿐이다.** 나머지 둘은 응시할 문항 자체가 없어 볼 수
					없었던 사람이며, 제출 현황 탭에서 같은 사람이 `BLOCKED`인 것과 같은 갈래다.

					💡 **`notInCode`를 따로 센다.** 그 개념이 코드에 없어 문제가 만들어지지 않은 것이라
					**못한 것이 아니다.** 막힌 사람과 한 칸에 넣으면 매니저가 둘을 구분하지 못한다.

					💡 **발행 전 집계는 임시 값이다.** 아직 응시하지 않은 인원이 빠져 있고, 발행 시점의
					값으로 굳는다. 화면은 발행 전에 '다시 보기 대상' 숫자를 아예 보여주지 않는다.

					🔴 **합격·불합격은 응시를 마친 사람(`AVAILABLE`)에게만 붙는다.** 아직 풀지 않은 문제는
					도달 단계가 0이라, 판정을 그대로 걸면 응시 중인 사람이 전부 불합격으로 잡힌다 —
					`retryTarget`·`stuckConceptCount`·`failedCount`가 모두 그 규칙을 따른다.

					⚠️ **`retryTargetCount`를 서버가 더해 주지 않는다** — 화면 규칙상 발행 전에는 그 합을
					감추기 때문에 `failedCount`와 `notAttendedCount`를 따로 준다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "결과 종합 조회 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED roundNo가 1 미만"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "PROJECT_ROUND_NOT_FOUND 그 번호의 회차가 없음 · MANAGER_SCOPE_NOT_FOUND 담당 범위 밖")
	})
	@GetMapping(value = "/projects/{projectId}/evaluations", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ProjectEvaluationSummaryResponse> findSummary(
			@Parameter(description = "조회할 프로젝트 ID") @PathVariable UUID projectId,
			@Parameter(description = "조회할 회차 번호", example = "1")
			@RequestParam(defaultValue = "1") @Min(1) int roundNo,
			@Parameter(description = "담당 반 하나로 좁힐 때만 지정합니다. 생략하면 담당 반 전체입니다.")
			@RequestParam(required = false) UUID classId,
			@Parameter(hidden = true) Authentication authentication
	) {
		return ResponseEntity.ok(
				evaluationService.findSummary(authentication.getName(), projectId, roundNo, classId));
	}

	@Operation(
			operationId = "findTraineeEvaluationDetail",
			summary = "[프로젝트 상세 - 결과 탭] 교육생 채점 결과 상세 조회 (매니저) | ✅ 사용 가능",
			description = """
					결과 탭에서 사람을 클릭했을 때 **오른쪽에 그리는 값**이다. 개념마다 도달 단계와
					축 4단계 사다리, 그 안에 접힌 채점 근거가 온다.

					## 응답 (200)

					| 필드 | 설명 |
					|---|---|
					| `resultStatus` | `AVAILABLE` · `IN_PROGRESS` · `INCOMPLETE`(중단) · `NOT_ATTENDED`(검증 세션을 못 함) · `INVALID` |
					| `notAttendedReason` | `NOT_ATTENDED`일 때만. `NO_SHOW`(안 봄) · `NOT_SUBMITTED`(팀 미제출) · `ANALYSIS_FAILED`(분석 실패) |
					| `concepts[]` | 개념별 `inCode` · `reachLevel` · `retryTarget` · `steps[]` |

					### concepts[].steps[]

					| 필드 | 설명 |
					|---|---|
					| `axisCode` · `stepNo` | L1~L4 = 코드이해 · 설계논리 · 대안비교 · 반례대응 |
					| `passed` | 그 단계 통과 여부. 도움을 받고 통과해도 true |
					| `helpCount` | 힌트를 받고 답한 횟수 0~2 |
					| `score` | 0~5 원점수. **화면에 노출하지 않는 내부 값** |
					| `note` | 채점 근거 한 줄 |

					⚠️ **실제로 물은 단계만 배열에 있다.** 앞 단계에서 미달하면 그 문제가 끝나므로 뒤 단계는
					아예 오지 않는다 — 화면은 그 자리를 '미도달' 빈 칸으로 그린다. **0점·불합격과 다르다.**

					⚠️ **`passed`와 `helpCount`를 따로 읽어야 한다.** 힌트를 2회까지 받고도 통과할 수 있고,
					2회 받고도 기준을 못 넘으면 불합격이다. 화면의 4범주(합격 · 합격(도움 1회) ·
					합격(도움 2회) · 불합격)가 이 둘의 조합이다.

					⚠️ **`note`는 리포트 생성 전에는 null이다** — 채점 근거가 리포트 스냅샷에 쌓이기 때문이다.
					발행 전에는 도달 단계와 통과 여부까지만 보여줄 수 있다.

					💡 **주고받은 대화 전문은 주지 않는다.** 매니저 화면의 계약이며 전문은 학생 리포트에만 있다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "교육생 결과 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "PROJECT_ROUND_NOT_FOUND 그 번호의 회차가 없음 · EVALUATION_TRAINEE_NOT_FOUND 담당 범위에 없는 교육생 · MANAGER_SCOPE_NOT_FOUND 담당 범위 밖의 기수")
	})
	@GetMapping(value = "/projects/{projectId}/evaluations/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<TraineeEvaluationDetailResponse> findTraineeDetail(
			@Parameter(description = "조회할 프로젝트 ID") @PathVariable UUID projectId,
			@Parameter(description = "조회할 교육생 ID") @PathVariable UUID userId,
			@Parameter(description = "조회할 회차 번호", example = "1")
			@RequestParam(defaultValue = "1") @Min(1) int roundNo,
			@Parameter(hidden = true) Authentication authentication
	) {
		return ResponseEntity.ok(
				evaluationService.findTraineeDetail(authentication.getName(), projectId, roundNo, userId));
	}
}
