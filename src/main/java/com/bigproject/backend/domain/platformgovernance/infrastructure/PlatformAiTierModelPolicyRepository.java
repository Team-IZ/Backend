package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformAiTierModelPolicy;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformGradingModelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 티어 ↔ 모델 매핑 정책(플랫폼 전역, 버전형).
 * (기능, 티어) 조합마다 활성 버전이 최대 1건이다 — 부분 UNIQUE 인덱스 uq_platform_ai_tier_model_policy_active.
 */
public interface PlatformAiTierModelPolicyRepository extends JpaRepository<PlatformAiTierModelPolicy, UUID> {

	/** 현재 활성 매핑 전체. SA-03 모델·단가 탭의 3티어 표를 채운다. */
	List<PlatformAiTierModelPolicy> findByStatus(PlatformGradingModelPolicy.Status status);

	Optional<PlatformAiTierModelPolicy> findByFeatureCodeAndTierCodeAndStatus(
			PlatformAiTierModelPolicy.FeatureCode featureCode,
			AiTier tierCode,
			PlatformGradingModelPolicy.Status status
	);

	/** (기능, 티어) 조합의 현재 최대 버전. 다음 버전 번호를 정할 때 쓴다. */
	Optional<PlatformAiTierModelPolicy> findFirstByFeatureCodeAndTierCodeOrderByPolicyVersionDesc(
			PlatformAiTierModelPolicy.FeatureCode featureCode,
			AiTier tierCode
	);
}
