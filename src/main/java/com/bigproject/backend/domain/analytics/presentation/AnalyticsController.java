package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ActionRequiredAnalyticsService;
import com.bigproject.backend.domain.analytics.application.CohortComparisonAnalyticsService;
import com.bigproject.backend.domain.analytics.application.GroupGapAnalyticsService;
import com.bigproject.backend.domain.analytics.application.RiskTraineeAnalyticsService;
import com.bigproject.backend.domain.analytics.domain.ComparisonSort;
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
@RequestMapping("/cohorts/{cohortId}/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

	private final RiskTraineeAnalyticsService riskTraineeAnalyticsService;
	private final CohortComparisonAnalyticsService cohortComparisonAnalyticsService;
	private final ActionRequiredAnalyticsService actionRequiredAnalyticsService;
	private final GroupGapAnalyticsService groupGapAnalyticsService;

	@Operation(
			summary = "조치 필요 경보 조회",
			description = """
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
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 기수 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기수를 찾을 수 없음")
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
			summary = "집단 미달 목록 조회",
			description = """
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
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 기수 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기수를 찾을 수 없음")
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
			summary = "회차별 기수 전체·반별 위험 교육생 비율 조회",
			description = """
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
					임계 구간 없이 단순 비교합니다. 클라이언트가 다시 계산할 필요는 없습니다.

					round_no는 (project_id, round_no) UNIQUE라 프로젝트마다 1부터 다시 시작합니다.
					기수에 미니프로젝트가 여러 건이면 같은 회차 번호가 여러 열에 나타나므로
					한 프로젝트의 흐름만 보려면 projectId로 좁힙니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "위험 교육생 비율 조회 성공"),
			@ApiResponse(responseCode = "400", description = "반 또는 회차 범위 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 기수 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기수를 찾을 수 없음")
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
					반 행 정렬 기준입니다.
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
				sort,
				authentication.getName()
		));
	}

	@Operation(
			summary = "두 기수의 검증 개념별 평균 도달 단계 비교",
			description = """
					같은 기관의 두 기수를 검증 개념(teaches_id) 단위로 맞대어 평균 도달 단계를 비교합니다.

					평균은 발행된 수업 진단 리포트의 활성 스냅샷에서 개념별 도달 단계 분포를 읽어
					Σ(도달 단계 × 인원) / Σ인원으로 계산합니다. 1단도 통과하지 못한 응시가 있으므로 척도는 0~4단입니다.
					응시 인원이 없으면 0단이 아니라 null이며, 0단은 측정 결과이고 null은 측정 자체가 없다는 뜻입니다.

					색 눈금은 회차별 위험 비율과 달리 절대 눈금이며 MG-02 히트맵과 같은 값입니다.
					0~4 정수 다섯 단계에 다섯 색을 대응시키되 평균은 연속값이므로 반올림으로 밴드를 배정하고,
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
			@ApiResponse(responseCode = "400", description = "비교 대상 기수가 같은 기관의 다른 기수가 아님"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 기수 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/cohort-comparison")
	public ResponseEntity<CohortComparisonResponse> findCohortComparison(
			@Parameter(description = "이번 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "비교할 지난 기수 ID이며 생략 시 비교 후보 목록만 반환합니다.")
			@RequestParam(required = false) UUID baselineCohortId,
			@Parameter(description = "정렬 기준이며 나빠진 순·좋아진 순·검증 개념 순을 지원합니다.", example = "WORSENED")
			@RequestParam(required = false, defaultValue = "WORSENED") ComparisonSort sort,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(cohortComparisonAnalyticsService.findCohortComparison(
				cohortId,
				baselineCohortId,
				sort,
				authentication.getName()
		));
	}
}
