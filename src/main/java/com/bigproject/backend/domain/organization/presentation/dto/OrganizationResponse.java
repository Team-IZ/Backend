package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 기관 단건/목록 응답. v2 IA의 SA-01 기관 목록과 SA-02 기관 상세 개요를 함께 채운다.
 *
 * <p>테넌트 경계: 슈퍼어드민은 기관의 <b>종합 지표</b>(기수·교육생 수, AI 사용량)와 오퍼레이터 계정만 본다.
 * 매니저는 수까지도 내려보내지 않는다 — 화면정의서 SA-02가 개요 탭 내용을
 * "상태 · 기수 목록 · 교육생 수 · 오퍼레이터"로 못 박았고, v1 대조에서 "매니저 로스터 읽기전용 조회 = 버림"으로
 * 판정했다(테넌트 경계 안의 사람이라 슈퍼어드민 화면에 둘 이유가 없다).
 */
@Schema(description = "기관 정보 (SA-01 목록 행 / SA-02 상세 개요 공용)")
public record OrganizationResponse(
		UUID organizationId,

		@Schema(description = "URL·외부 연동에 쓰는 기관 slug(예: greencompany). 지정하지 않은 기관은 null.",
				example = "greencompany", nullable = true)
		String slug,

		@Schema(description = "화면 표시용 짧은 코드(예: ORG_GRN_4F21). 권한·테넌트 판정에는 쓰지 않는다. 미지정이면 null.",
				example = "ORG_GRN_4F21", nullable = true)
		String displayCode,

		String name,

		@Schema(description = """
				초대 허용 이메일 도메인(예: codebase.ac.kr). 이 도메인 밖 주소로는 오퍼레이터를 초대할 수 없다.
				null이면 도메인 제한을 적용하지 않는다.""",
				example = "codebase.ac.kr", nullable = true)
		String emailDomain,

		OrganizationStatus status,

		@Schema(description = """
				파생 배지: 활성 오퍼레이터가 0명이라 기관이 아직 시작되지 않은 상태(목업 `오퍼레이터 미배정`).
				저장 상태가 아니므로 status는 그대로 ACTIVE다.""")
		boolean operatorUnassigned,

		@Schema(description = """
				파생 배지: 이번 달 AI 비용이 월 예산을 초과한 상태(목업 `예산 초과`). 서비스 중단은 아니다.
				저장 상태가 아니므로 status는 그대로 ACTIVE다.""")
		boolean budgetExceeded,

		@Schema(description = "기관 소속 오퍼레이터 계정. 목록의 `박지현 외 1`, 상세의 `박지현 · 이도윤`을 렌더링한다.")
		List<Operator> operators,

		@Schema(description = "기수 수(전체/진행/종료)")
		CohortCounts cohorts,

		int traineeCount,

		@Schema(description = "진행 중 세션 수. 시작됐고 아직 끝나지 않은 세션(IN_PROGRESS·PAUSED)만 센다.")
		int activeSessionCount,

		BigDecimal currentMonthAiCost,

		@Schema(description = "활성 정책의 월 AI 예산. 상세 개요의 `예산 $600 대비 69%` 계산에 쓴다.")
		BigDecimal monthlyAiBudget,

		@Schema(description = "예산 소진율(0~1). 예산이 0이면 null.", nullable = true)
		BigDecimal budgetUsageRate,

		@Schema(description = "통화. 플랫폼 공통 USD. 활성 정책이 없으면 null", nullable = true)
		String currencyCode,

		int dataRetentionDays,

		@Schema(description = "신규 기수 공개 범위 기본값. 활성 정책이 없으면 null", nullable = true)
		DisclosureScope defaultDisclosureScope,

		Instant createdAt,

		@Schema(description = "soft-delete 시각. 살아 있는 기관은 null", nullable = true)
		Instant deletedAt
) {

	@Schema(description = "오퍼레이터 계정 요약")
	public record Operator(UUID memberId, String name, String email) {
	}

	@Schema(description = "기수 수 분해. 목업 `기수 3 (진행 2 · 종료 1)`")
	public record CohortCounts(int total, int running, int closed) {
	}
}
