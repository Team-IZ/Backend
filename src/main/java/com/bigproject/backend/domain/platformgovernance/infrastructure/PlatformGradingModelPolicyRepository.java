package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.PlatformGradingModelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 채점 모델 정책(플랫폼 전역, 버전형). 활성 버전은 항상 최대 1건이다. */
public interface PlatformGradingModelPolicyRepository extends JpaRepository<PlatformGradingModelPolicy, UUID> {

	Optional<PlatformGradingModelPolicy> findByStatus(PlatformGradingModelPolicy.Status status);

	/** 다음 버전 번호를 정하기 위한 현재 최대 버전. 정책이 하나도 없으면 비어 있다. */
	Optional<PlatformGradingModelPolicy> findFirstByOrderByPolicyVersionDesc();
}
