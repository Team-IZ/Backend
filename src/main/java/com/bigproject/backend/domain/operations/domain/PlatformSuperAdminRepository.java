package com.bigproject.backend.domain.operations.domain;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 슈퍼어드민 계정 조회·상태 변경 포트. 목업 SA-03 ② `슈퍼어드민 계정` 탭.
 *
 * <p>{@code app_user}는 JPA 엔티티가 없고 auth·member 도메인이 소유한 테이블이라,
 * organization 도메인의 통계 포트와 같은 방식으로 <b>읽기·상태변경 SQL</b>로만 접근한다.
 *
 * <p>슈퍼어드민은 기관에 속하지 않으므로 {@code org_id IS NULL}이며 {@code role.code = 'SUPER_ADMIN'}이다.
 */
public interface PlatformSuperAdminRepository {

	/** 슈퍼어드민 계정 전체(삭제되지 않은 것). 생성 시각 오름차순. */
	List<SuperAdminAccount> findSuperAdmins();

	Optional<SuperAdminAccount> findSuperAdmin(UUID memberId);

	/** 활성 슈퍼어드민 수. 1이면 마지막 1인이라 정지가 차단된다. */
	int countActiveSuperAdmins();

	/** 계정 상태 변경. 영향받은 행 수를 반환한다. */
	int updateStatus(UUID memberId, OperatorAccountStatus status);

	record SuperAdminAccount(
			UUID memberId,
			String name,
			String email,
			OperatorAccountStatus status,
			Instant lastLoginAt,
			Instant createdAt
	) {
	}
}
