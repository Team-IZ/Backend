package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ActionRequiredAnalyticsService;
import com.bigproject.backend.domain.analytics.application.CohortComparisonAnalyticsService;
import com.bigproject.backend.domain.analytics.application.GroupGapAnalyticsService;
import com.bigproject.backend.domain.analytics.application.RiskTraineeAnalyticsService;
import com.bigproject.backend.domain.analytics.domain.ComparisonSort;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeLevel;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.presentation.dto.ActionRequiredResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.CohortComparisonResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.GroupGapResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Analytics", description = "기수 분석 격자 조회")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AnalyticsController {

	private final RiskTraineeAnalyticsService riskTraineeAnalyticsService;
	private final CohortComparisonAnalyticsService cohortComparisonAnalyticsService;
	private final ActionRequiredAnalyticsService actionRequiredAnalyticsService;
	private final GroupGapAnalyticsService groupGapAnalyticsService;

	@Operation(
			operationId = "findCohortActionsRequired",
			summary = "조치 필요 경보 조회 | ⚠️ 사용 불가",
			description = """
					⚠️ **API 테스트를 위한 더미 데이터 없음** — 이 응답의 원천은 발행된 리포트 스냅샷과
					회차 채점 결과인데 현재 어느 기수에도 그 데이터가 없습니다. 호출하면 200과 함께 빈
					격자만 돌아오므로 **응답 형태를 확인하는 용도로도 쓸 수 없습니다.** 스펙과 구현은
					완성돼 있어 시드가 준비되면 그대로 사용 가능으로 바뀝니다.

					오퍼레이터 대시보드의 '조치 필요' 네 경보를 한 번에 조회합니다.

					담당 매니저 미배정, 검증 개념 공백, 집단 미달, 면담 적체이며 유형별로 가장 나쁜 한 건씩
					올립니다. 최댓값 한 건만 고르므로 별도 임계값 정책이 없습니다.
					해당 경보가 없으면 그 필드는 null이고 actionCount는 null이 아닌 경보 수입니다.

					경보는 파생 조회이며 해소·무시 상태를 저장하지 않습니다. 원인이 사라지면 경보도
					사라지므로 조치 완료를 기록하는 쓰기 API가 없습니다.

					회차 범위는 미니프로젝트로 좁히되 면담 적체만 빅프로젝트 회차도 포함합니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조치 필요 경보 조회 성공"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/actions")
	public ResponseEntity<ActionRequiredResponse> findActionsRequired(
			@Parameter(description = "분석할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(
				actionRequiredAnalyticsService.findActionsRequired(cohortId, authentication.getName()));
	}

	@Operation(
			operationId = "findCohortGroupGaps",
			summary = "집단 미달 목록 조회 | ⚠️ 사용 불가",
			description = """
					⚠️ **API 테스트를 위한 더미 데이터 없음** — 이 응답의 원천은 발행된 리포트 스냅샷과
					회차 채점 결과인데 현재 어느 기수에도 그 데이터가 없습니다. 호출하면 200과 함께 빈
					격자만 돌아오므로 **응답 형태를 확인하는 용도로도 쓸 수 없습니다.** 스펙과 구현은
					완성돼 있어 시드가 준비되면 그대로 사용 가능으로 바뀝니다.

					기수 전체에서 반 인원의 절반을 넘는 인원이 한 검증 개념에서 2단 이하인 조합을 조회합니다.

					개인 위험 사유가 아니라 반 문제로 분류하며, 시스템은 표시까지만 하고 이후 처리는
					기관 판단입니다.

					분자는 실제 응시를 마친 인원 중 2단 이하만 셉니다. 미응시·무효 확정은 0단으로
					치환하지 않으므로 분자에서 빠지고 분모(반 인원 전체)에만 남습니다.

					발행된 리포트에 의존하지 않고 원천에서 실시간 집계하므로 발행 전에도 값이 나옵니다.
					목록이 비어 있으면 emptyReasonCode로 평가 자체가 없는 경우와 미달이 실제로 0건인
					경우를 구분합니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "집단 미달 목록 조회 성공"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/group-gaps")
	public ResponseEntity<GroupGapResponse> findGroupGaps(
			@Parameter(description = "분석할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(groupGapAnalyticsService.findGroupGaps(cohortId, authentication.getName()));
	}

	@Operation(
			operationId = "findCohortRiskTraineeRates",
			summary = "회차별 기수 전체·반별 위험 교육생 비율 조회 | ⚠️ 사용 불가",
			description = """
					⚠️ **API 테스트를 위한 더미 데이터 없음** — 이 응답의 원천은 발행된 리포트 스냅샷과
					회차 채점 결과인데 현재 어느 기수에도 그 데이터가 없습니다. 호출하면 200과 함께 빈
					격자만 돌아오므로 **응답 형태를 확인하는 용도로도 쓸 수 없습니다.** 스펙과 구현은
					완성돼 있어 시드가 준비되면 그대로 사용 가능으로 바뀝니다.

					선택 기수의 미니프로젝트 회차별로 기수 전체와 반별 위험 교육생 비율을 계산합니다.

					분모는 회차의 INITIAL 수행 대상 교육생에서 미집계 3종을 뺀 인원입니다.
					미집계는 미응시(NOT_ATTENDED), 중단(SESSION_INCOMPLETE), 무효 응시(CONFIRMED_INVALID)이며
					무효 확인 중(PENDING)은 아직 확정되지 않아 분모에 남습니다.

					분자는 위험 유형(단계 하락·지속 저점) 중 하나 이상이 활성으로 일치한 고유 교육생 수이며,
					한 교육생이 여러 유형에 해당해도 1명으로 셉니다.
					위험 유형 INVALID_ATTEMPT는 해당 교육생이 무효 응시로 분모에서 이미 빠지므로 분자에 넣지 않습니다.

					집계 상태는 회차 생명주기가 아니라 발행된 리포트 유무로 판정합니다.
					발행본이 없으면 riskRate는 0이 아니라 null이고 aggregationStatus로 원인을 구분합니다.
					빅프로젝트는 위험 판정식이 달라 이 격자에 포함하지 않습니다.

					반 행의 comparisonToCohort는 같은 회차의 기수 전체 비율과 견준 방향이며
					임계 구간 없이 단순 비교합니다. 팀 행은 기수 전체가 아니라 소속 반 전체
					비율과 비교하며, 그 반 행은 classes에 함께 내려갑니다. 클라이언트가 다시
					계산할 필요는 없습니다.

					round_no는 (project_id, round_no) UNIQUE라 프로젝트마다 1부터 다시 시작합니다.
					기수에 미니프로젝트가 여러 건이면 같은 회차 번호가 여러 열에 나타나므로
					한 프로젝트의 흐름만 보려면 projectId로 좁힙니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "위험 교육생 비율 조회 성공"),
			@ApiResponse(responseCode = "400", description = "CLASSROOM_NOT_IN_COHORT 기수에 속하지 않은 반 · PROJECT_NOT_IN_COHORT 기수의 미니프로젝트가 아님 · ROUND_RANGE_INVALID 회차 범위 오류 · TEAM_LEVEL_PROJECT_REQUIRED 팀 계층인데 프로젝트 미지정 · TEAM_LEVEL_SINGLE_CLASSROOM_REQUIRED 팀 계층인데 반이 하나가 아님"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음 · CLASSROOM_NOT_FOUND 팀 계층에서 지정한 반을 찾을 수 없음")
	})
	@GetMapping("/risk-trainees")
	public ResponseEntity<RiskTraineeRateResponse> findRiskTraineeRates(
			@Parameter(description = "분석할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = """
					조회 대상을 한 미니프로젝트로 좁힙니다. 생략 시 기수의 모든 미니프로젝트를 조회합니다.
					회차 번호는 프로젝트마다 1부터 다시 시작하므로 미니프로젝트가 여러 건인 기수에서
					한 프로젝트의 회차 흐름만 보려면 지정해야 합니다.
					""")
			@RequestParam(required = false) UUID projectId,
			@Parameter(description = "조회할 반 ID 목록이며 생략 시 기수의 모든 반을 조회합니다.")
			@RequestParam(required = false) List<UUID> classroomId,
			@Parameter(description = "조회 시작 회차 번호이며 생략 시 1차부터 조회합니다.", example = "1")
			@RequestParam(required = false) @Min(1) Integer fromRoundNo,
			@Parameter(description = "조회 종료 회차 번호이며 생략 시 마지막 회차까지 조회합니다.", example = "4")
			@RequestParam(required = false) @Min(1) Integer toRoundNo,
			@Parameter(description = """
					행 계층입니다. TEAM이면 projectId와 classroomId 한 건이 모두 필요합니다.
					팀 번호는 반 안에서만 유일하고 팀은 프로젝트에 종속이라 둘 다 좁혀야 행이 성립합니다.
					""", example = "CLASS")
			@RequestParam(required = false, defaultValue = "CLASS") RiskTraineeLevel level,
			@Parameter(description = """
					반·팀 행 정렬 기준입니다.
					RECENT_ROUND_WORST(최근 발행 회차 나쁜 순) / WORSE_ROUND_COUNT(기준보다 나쁜 회차가 많은 순)
					/ EXCLUSION_COUNT(미집계 많은 순) / NAME(이름순)
					""", example = "RECENT_ROUND_WORST")
			@RequestParam(required = false, defaultValue = "RECENT_ROUND_WORST") RiskTraineeSort sort,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(riskTraineeAnalyticsService.findRiskTraineeRates(
				cohortId,
				projectId,
				classroomId,
				fromRoundNo,
				toRoundNo,
				level,
				sort,
				authentication.getName()
		));
	}

	@Operation(
			operationId = "findCohortComparison",
			summary = "두 기수의 검증 개념별 평균 도달 단계 비교 | ⚠️ 사용 불가",
			description = """
					⚠️ **API 테스트를 위한 더미 데이터 없음** — 이 응답의 원천은 발행된 리포트 스냅샷과
					회차 채점 결과인데 현재 어느 기수에도 그 데이터가 없습니다. 호출하면 200과 함께 빈
					격자만 돌아오므로 **응답 형태를 확인하는 용도로도 쓸 수 없습니다.** 스펙과 구현은
					완성돼 있어 시드가 준비되면 그대로 사용 가능으로 바뀝니다.

					같은 기관의 두 기수를 검증 개념(teaches_id) 단위로 맞대어 평균 도달 단계를 비교합니다.

					평균은 발행된 수업 진단 리포트의 활성 스냅샷에서 개념별 도달 단계 분포를 읽어
					Σ(도달 단계 × 인원) / Σ인원으로 계산합니다.
					응시 인원이 없으면 값이 0인 것이 아니라 null이며, 낮은 평균은 측정 결과이고 null은 측정 자체가 없다는 뜻입니다.

					색 눈금은 회차별 위험 비율과 달리 절대 눈금이며 목업 색상표와 같은 값입니다.
					1~4 정수 네 단계(1단·2단·3단·4단)에 네 색을 대응시키며, 평균은 연속값이므로 정수 경계 미만을
					버림(floor)해 밴드를 배정합니다 — 1단은 0 이상 2단 미만, 2단은 2 이상 3단 미만,
					3단은 3 이상 4단 미만, 4단은 4단입니다.
					서버가 levelBand와 밴드 경계를 함께 내려주므로 클라이언트가 다시 계산할 필요는 없습니다.

					변화는 이번 기수 평균에서 지난 기수 평균을 뺀 값이며 -0.3단 이하가 나빠짐, +0.3단 이상이 좋아짐입니다.
					한쪽 기수에 없는 개념, 다른 개념으로 병합된 개념(teaches.status=MERGED),
					반복 개념 집계 산식이 확정되지 않은 개념은 뺄셈이 성립하지 않아 NOT_COMPARABLE로 내려갑니다.

					검증 개념이 기수마다 다르면 같은 프로젝트라도 비교할 수 없으므로 개념 단위로만 맞춥니다.
					비교 대상 기수를 지정하지 않으면 드롭다운 후보 목록만 채워 돌려줍니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 간 비교 조회 성공"),
			@ApiResponse(responseCode = "400", description = "BASELINE_COHORT_INVALID 비교 대상이 같은 기관의 다른 기수가 아님"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/cohort-comparison")
	public ResponseEntity<CohortComparisonResponse> findCohortComparison(
			@Parameter(description = "이번 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "비교할 지난 기수 ID이며 생략 시 비교 후보 목록만 반환합니다.")
			@RequestParam(required = false) UUID baselineCohortId,
			@Parameter(description = "정렬 기준이며 나빠진 순·좋아진 순·검증 개념 순을 지원합니다.", example = "WORSENED")
			@RequestParam(required = false, defaultValue = "WORSENED") ComparisonSort sort,
			@Parameter(description = """
					같은 교안 버전을 쓴 개념만 남깁니다.
					교안이 바뀌면 평균 차이가 교육생 변화인지 교안 변화인지 갈라 볼 수 없어 걸러냅니다.
					한쪽 기수에 없던 개념도 함께 제외됩니다.
					""", example = "false")
			@RequestParam(required = false, defaultValue = "false") boolean sameCurriculumOnly,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(cohortComparisonAnalyticsService.findCohortComparison(
				cohortId,
				baselineCohortId,
				sort,
				sameCurriculumOnly,
				authentication.getName()
		));
	}
}
