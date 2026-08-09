package com.bigproject.backend.domain.organization.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * organization.status에 실제로 저장되는 값. DB CHECK(ck_organization_status_1)와 1:1로 맞춘다.
 *
 * v2 IA(SA-01/SA-02)의 기관 배지는 `활성 / 정지 / 예산 초과 / 오퍼레이터 미배정` 4종이지만,
 * 뒤의 두 개는 저장 상태가 아니라 <b>파생 배지</b>다. 목업 SA-02 개요에서 파이널랩은
 * `상태 = 활성`, `오퍼레이터 = 미배정`으로 따로 표시되고 헤더 배지만 '오퍼레이터 미배정'이다.
 * 그래서 기존 enum에 있던 BUDGET_EXCEEDED / PENDING_LEAD_MANAGER는 제거하고,
 * {@code OrganizationResponse.budgetExceeded} / {@code operatorUnassigned} 플래그로 내려보낸다.
 * (두 값은 DB CHECK에 없어 애초에 저장도 불가능했다.)
 */
@Schema(name = "OrganizationStatus", description = "기관 운영 상태. ACTIVE(활성) · SUSPENDED(정지) · DELETION_PENDING(삭제 예정) · DELETED(삭제됨). `예산 초과`·`오퍼레이터 미배정`은 저장 상태가 아니라 파생 배지다.", enumAsRef = true)
public enum OrganizationStatus {
	ACTIVE,
	SUSPENDED,
	/**
	 * DB CHECK에는 있으나 현재 삭제 흐름은 이 중간 상태를 거치지 않고 곧바로 DELETED로 전이한다
	 * ({@link Organization#softDelete}). CHECK와의 정합을 위해 값만 유지한다.
	 */
	DELETION_PENDING,
	DELETED
}
