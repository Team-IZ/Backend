package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.OrganizationGradingCalibration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** 기관별 재캘리브레이션 진행 상태. */
public interface OrganizationGradingCalibrationRepository
		extends JpaRepository<OrganizationGradingCalibration, UUID> {

	List<OrganizationGradingCalibration> findByCalibrationVersionId(UUID calibrationVersionId);

	/**
	 * 캘리브레이션 버전의 상태별 기관 수. 진행률 화면용이며 기관 수가 많아도 행을 다 읽지 않는다.
	 * 반환값은 (status, count) 쌍의 목록이다.
	 */
	@Query("""
			SELECT c.status, COUNT(c)
			FROM OrganizationGradingCalibration c
			WHERE c.calibrationVersionId = :calibrationVersionId
			GROUP BY c.status
			""")
	List<Object[]> countByStatus(@Param("calibrationVersionId") UUID calibrationVersionId);
}
