package com.bigproject.backend.domain.usagemetering.presentation;

import com.bigproject.backend.domain.usagemetering.application.OperationsService;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

@Tag(name = "Usage Metering", description = "AI 호출량·토큰·비용, 저장소 사용량, 기관 한도, 비용 집계 API (v2 IA: SA-02 ③④ / OP-06 ⑤)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/organizations/{organizationId}/operations")
@RequiredArgsConstructor
public class OperationsController {

	private final OperationsService operationsService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "기관 월별 저장량·활동·AI 비용 조회",
			description = """
					지정한 기관의 특정 월(period) 사용량을 조회한다. \
					슈퍼어드민의 SA-02 ③ `사용량 · AI 비용` 탭과 오퍼레이터의 OP-06 ⑤ `비용` 탭이 함께 쓴다.

					**권한**
					- 슈퍼어드민: 모든 기관 조회 가능
					- 오퍼레이터: 자기 기관만 조회 가능 (다른 기관이면 403)
					- 매니저·교육생: 접근 불가 — 목업 주석: "매니저에게는 비용을 보여주지 않는다. \
					보이면 '비싸니까 세션 짧게'라는 잘못된 압력이 생긴다."

					**요청**
					- organizationId (경로)
					- period (선택, yyyy-MM 형식, 예: 2026-07): 생략하면 이번 달(UTC 기준)
					- cohortId (선택): 기수별·반별 내역의 범위. OP-06 ⑤ 비용 탭은 상단 기수 스위처가 범위를 정하므로 \
					그 기수를 넘긴다. 생략하면 기관 전체(SA-02 ③ 슈퍼어드민 화면).

					**응답**
					- storage: 코드 제출물·문답 원문·채점 근거·리포트별 저장 바이트 + 전월 대비 증감률. \
					주기 스냅샷을 합산하면 중복 집계되므로 카테고리별 최신 스냅샷(시점 값)을 사용한다.
					- activity: 활성 교육생·완료 세션·채점 건수·생성 리포트 수
					- aiCost: 총 비용·월 예산·소진율·초과 여부·전월 대비 증감률·합계 행·(용도, 모델)별 내역
					- cohortCosts / classCosts: 기수별·반별 비용

					증감률은 전월 값이 0이면 계산할 수 없어 null을 반환한다(화면에서는 `—`).

					**집계 실패 (목업 case 6)** — 기간 내 집계 실패 스냅샷이 있으면 `USAGE_UNAVAILABLE`(503)을 반환한다. \
					실패분을 빼고 남은 것만 더해 0처럼 보여주지 않는다 — "안 쓴 것"과 "못 읽은 것"은 다르고, \
					0으로 그리면 청구액이 실제보다 작아 보인다. 화면은 이 오류로 **사용량 패널만** 오류 상태로 바꾸고 \
					기관 정보·다른 탭은 그대로 보여준다.

					**집계 출처** — aggregationSource가 SNAPSHOT이면 organization_usage_snapshot 기준이고, \
					LIVE면 ai_usage를 그 자리에서 합산한 값이다. 스냅샷 수집 배치가 붙기 전에는 LIVE로 내려온다.

					**단가 미설정 처리** — 단가가 없는 호출은 비용을 0으로 더하지 않고 합계에서 제외하며, \
					제외된 건수를 aiCost.unpricedCallCount로, 합계가 완전한지를 aiCost.costComplete로 알려준다.

					**활동량 집계 기준** — v07에서 06_MEAS·10_RPT 테이블이 생겨 LIVE 경로에서도 실제 값을 센다.
					- 완료 세션: assessment_session.ended_at이 기간에 들어온 COMPLETED 세션
					- 채점 회차: project_assessment_round.submission_due_at이 기간에 들어온 회차 \
					(채점 실행 시각 컬럼이 없어 마감을 실행 시점으로 본다)
					- 발행 리포트: report.published_at이 기간에 들어온 리포트
					- classCosts의 sessionCount: 교육생 반 배정을 타고 집계하며, 배정이 해제된 교육생은 제외
					"""
	)
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
	@GetMapping("/usage")
	public ResponseEntity<OrganizationUsageResponse> findUsage(
			@PathVariable UUID organizationId,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth period,
			@RequestParam(required = false) UUID cohortId
	) {
		YearMonth resolvedPeriod = period == null ? YearMonth.now(ZoneOffset.UTC) : period;
		return ResponseEntity.ok(operationsService.findUsage(organizationId, resolvedPeriod, cohortId));
	}

	@Operation(
			summary = "기관 운영 설정 조회",
			description = """
					지정한 기관의 현재 활성(ACTIVE) 운영 정책을 조회한다. 목업 SA-02 ④ 설정 탭.

					**요청**
					- organizationId (경로)

					**응답**
					- organizationStatus: 기관 상태
					- monthlyAiBudget / currencyCode: 월 AI 예산과 통화
					- dataRetentionDays: 데이터 보존기간
					- defaultDisclosureScope: 신규 기수 공개 범위 기본값 (SUMMARY/PRIVATE/FULL)
					- policyVersion: 현재 정책 버전
					- 기관에 활성 정책이 없으면(정상 생성 경로를 거치지 않은 데이터 등) 500 오류가 발생한다.

					**아직 채워지지 않는 값** — monthlyTokenLimit, githubOrgIntegrationEnabled, \
					zipUploadEnabled, contributionAnalysisEnabled (organization_policy 컬럼 없음 → null)
					"""
	)
	@PreAuthorize("hasRole('SUPER_ADMIN')")
	@GetMapping("/settings")
	public ResponseEntity<OperationSettingResponse> findSettings(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(operationsService.findSettings(organizationId));
	}

	@Operation(
			summary = "기관 운영 설정 변경",
			description = """
					지정한 기관의 운영 설정을 변경한다. organization_policy는 append-only 이력 테이블이라 \
					기존 설정을 수정하는 게 아니라 기존 활성 버전을 SUPERSEDED로 닫고 새 버전을 발급하는 방식으로 동작한다.

					설정 변경은 슈퍼어드민 전용이다(목업상 진입점이 SA-02 ④ 설정 탭 하나뿐). \
					오퍼레이터는 사용량 조회만 가능하다.

					**요청** (부분 수정이 아니라 아래 값을 모두 포함해서 보내야 한다)
					- organizationId (경로)
					- organizationStatus (필수): ACTIVE 또는 SUSPENDED만 직접 지정 가능
					- monthlyAiBudget (필수)
					- dataRetentionDays (필수, 30~3650일)
					- defaultDisclosureScope (필수)
					- monthlyTokenLimit, githubOrgIntegrationEnabled, zipUploadEnabled, contributionAnalysisEnabled (선택)

					**응답**
					- 새로 발급된 버전 기준의 운영 설정
					- 삭제된 기관이면 409를 반환한다.

					⚠ monthlyTokenLimit / githubOrgIntegrationEnabled / zipUploadEnabled / \
					contributionAnalysisEnabled는 organization_policy에 컬럼이 아직 없어 **값을 보내도 저장되지 않고** \
					응답에 null이 반환된다. 프론트가 설정 화면을 먼저 만들 수 있도록 계약에만 포함해 둔 필드다.
					"""
	)
	@PreAuthorize("hasRole('SUPER_ADMIN')")
	@PutMapping("/settings")
	public ResponseEntity<OperationSettingResponse> updateSettings(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateOperationSettingRequest request
	) {
		OperationSettingResponse response = operationsService.updateSettings(
				organizationId, request, currentUserResolver.resolveCurrentMemberId()
		);
		return ResponseEntity.ok(response);
	}
}
