package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.PlatformGradingCalibrationVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 플랫폼 캘리브레이션 버전. 결과 비교의 기준이 되는 ACTIVE 버전은 최대 1건이다. */
public interface PlatformGradingCalibrationVersionRepository
		extends JpaRepository<PlatformGradingCalibrationVersion, UUID> {

	Optional<PlatformGradingCalibrationVersion> findByStatus(PlatformGradingCalibrationVersion.Status status);

	boolean existsByVersionCode(String versionCode);

	/** 진행 중(PENDING·RUNNING)인 버전들. 재캘리브레이션이 겹치지 않게 확인하는 데 쓴다. */
	List<PlatformGradingCalibrationVersion> findByStatusIn(List<PlatformGradingCalibrationVersion.Status> statuses);
}
