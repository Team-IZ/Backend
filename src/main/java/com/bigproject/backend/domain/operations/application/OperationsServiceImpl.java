package com.bigproject.backend.domain.operations.application;

import com.bigproject.backend.domain.operations.domain.AiModel;
import com.bigproject.backend.domain.operations.domain.AiUsage;
import com.bigproject.backend.domain.operations.domain.OrganizationPolicy;
import com.bigproject.backend.domain.operations.domain.StorageUsageSnapshot;
import com.bigproject.backend.domain.operations.domain.repository.AiUsageRepository;
import com.bigproject.backend.domain.operations.domain.repository.OrganizationPolicyRepository;
import com.bigproject.backend.domain.operations.domain.repository.StorageUsageSnapshotRepository;
import com.bigproject.backend.domain.operations.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.operations.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.operations.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 조회 트랜잭션. 쓰기가 필요한 메서드에만 @Transactional을 개별로 얹는다.
public class OperationsServiceImpl implements OperationsService {

	// organization 도메인 리포지토리를 함께 사용한다: 기관 존재 검증, organization.status 조회(운영 설정 응답에 필요)를 위함.
	private final OrganizationRepository organizationRepository;
	private final OrganizationPolicyRepository organizationPolicyRepository;
	private final StorageUsageSnapshotRepository storageUsageSnapshotRepository;
	private final AiUsageRepository aiUsageRepository;

	@Override
	public OrganizationUsageResponse findUsage(UUID organizationId, YearMonth period) {
		assertOrganizationExists(organizationId);

		Instant from = period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant to = period.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

		return new OrganizationUsageResponse(
				organizationId,
				period,
				resolveStorageUsage(organizationId, from, to),
				resolveActivityUsage(),
				resolveAiCostUsage(organizationId, from, to)
		);
	}

	@Override
	public OperationSettingResponse findSettings(UUID organizationId) {
		Organization organization = getOrganizationOrThrow(organizationId);
		OrganizationPolicy policy = getActivePolicyOrThrow(organizationId);
		return toResponse(organization, policy);
	}

	@Override
	@Transactional
	public OperationSettingResponse updateSettings(UUID organizationId, UpdateOperationSettingRequest request, UUID requesterId) {
		Organization organization = getOrganizationOrThrow(organizationId);
		OrganizationPolicy currentPolicy = getActivePolicyOrThrow(organizationId);

		// organization_policy는 append-only 이력 테이블이므로 기존 행을 고치지 않고,
		// 현재 활성 버전은 SUPERSEDED로 닫은 뒤 새 버전을 INSERT한다.
		currentPolicy.supersede();
		OrganizationPolicy nextPolicy = OrganizationPolicy.createNextVersion(
				currentPolicy,
				request.monthlyAiBudget(),
				request.dataRetentionDays(),
				request.defaultDisclosureScope(),
				requesterId
		);
		organizationPolicyRepository.save(nextPolicy);

		organization.changeStatus(request.organizationStatus());
		organization.touchUpdatedBy(requesterId);

		return toResponse(organization, nextPolicy);
	}

	// storage_usage_snapshot의 카테고리별 byte_count 합계를 OrganizationUsageResponse.StorageUsage의 4개 항목에 매핑한다.
	// CURRICULUM_PDF, DATABASE 카테고리는 별도 항목이 없어 totalBytes에만 반영된다.
	private OrganizationUsageResponse.StorageUsage resolveStorageUsage(UUID organizationId, Instant from, Instant to) {
		List<StorageUsageSnapshot> snapshots = storageUsageSnapshotRepository
				.findByOrgIdAndAggregationStatusAndAsOfAtGreaterThanEqualAndAsOfAtLessThan(
						organizationId, StorageUsageSnapshot.AggregationStatus.SUCCEEDED, from, to
				);

		Map<StorageUsageSnapshot.StorageCategory, Long> byteCountByCategory = snapshots.stream()
				.collect(Collectors.groupingBy(
						StorageUsageSnapshot::getStorageCategory,
						Collectors.summingLong(snapshot -> snapshot.getByteCount() == null ? 0L : snapshot.getByteCount())
				));

		long totalBytes = byteCountByCategory.values().stream().mapToLong(Long::longValue).sum();

		return new OrganizationUsageResponse.StorageUsage(
				totalBytes,
				byteCountByCategory.getOrDefault(StorageUsageSnapshot.StorageCategory.CODE_ARTIFACT, 0L),
				byteCountByCategory.getOrDefault(StorageUsageSnapshot.StorageCategory.SESSION_TRANSCRIPT, 0L),
				byteCountByCategory.getOrDefault(StorageUsageSnapshot.StorageCategory.SCORE_EVIDENCE, 0L),
				byteCountByCategory.getOrDefault(StorageUsageSnapshot.StorageCategory.REPORT_EXPORT, 0L)
		);
	}

	// activeTrainees/completedSessions/gradingRounds/generatedReports는 각각 member/session/grading/report 도메인의
	// 엔티티가 있어야 계산할 수 있다. 이번 작업 범위(organization, operations)에는 해당 엔티티가 없으므로 0으로 채워둔다.
	private OrganizationUsageResponse.ActivityUsage resolveActivityUsage() {
		return new OrganizationUsageResponse.ActivityUsage(0, 0, 0, 0);
	}

	private OrganizationUsageResponse.AiCostUsage resolveAiCostUsage(UUID organizationId, Instant from, Instant to) {
		List<AiUsage> usages = aiUsageRepository.findByOrgIdAndOccurredAtBetween(organizationId, from, to);

		BigDecimal totalCost = usages.stream()
				.map(AiUsage::resolveCost)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal monthlyBudget = organizationPolicyRepository.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.map(OrganizationPolicy::getMonthlyAiBudget)
				.orElse(BigDecimal.ZERO);

		boolean budgetExceeded = monthlyBudget.compareTo(BigDecimal.ZERO) > 0 && totalCost.compareTo(monthlyBudget) > 0;

		List<OrganizationUsageResponse.ModelUsage> models = groupByFeatureAndModel(usages).entrySet().stream()
				.map(entry -> toModelUsage(entry.getKey(), entry.getValue()))
				.toList();

		return new OrganizationUsageResponse.AiCostUsage(totalCost, monthlyBudget, budgetExceeded, models);
	}

	// (기능 코드, 모델) 조합별로 묶어 모델별 사용량 내역(ModelUsage)을 만든다.
	private Map<UsageGroupKey, List<AiUsage>> groupByFeatureAndModel(List<AiUsage> usages) {
		return usages.stream()
				.collect(Collectors.groupingBy(usage -> new UsageGroupKey(usage.getFeatureCode(), usage.getModel())));
	}

	private OrganizationUsageResponse.ModelUsage toModelUsage(UsageGroupKey key, List<AiUsage> group) {
		long calls = group.size();
		long inputTokens = group.stream().mapToLong(AiUsage::getInputTokenCount).sum();
		long outputTokens = group.stream().mapToLong(AiUsage::getOutputTokenCount).sum();
		BigDecimal cost = group.stream().map(AiUsage::resolveCost).reduce(BigDecimal.ZERO, BigDecimal::add);
		// 100만 토큰당 단가 = 건당 입력 단가(토큰당) * 1,000,000. 같은 그룹 내 단가는 동일하다고 가정하고 첫 건 값을 사용한다.
		BigDecimal unitPricePerMillionTokens = group.get(0).getInputUnitPrice().multiply(BigDecimal.valueOf(1_000_000));

		return new OrganizationUsageResponse.ModelUsage(
				key.featureCode().name(),
				key.model().getDisplayName(),
				calls,
				inputTokens,
				outputTokens,
				unitPricePerMillionTokens,
				cost
		);
	}

	private void assertOrganizationExists(UUID organizationId) {
		if (!organizationRepository.existsById(organizationId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId);
		}
	}

	private Organization getOrganizationOrThrow(UUID organizationId) {
		return organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId));
	}

	private OrganizationPolicy getActivePolicyOrThrow(UUID organizationId) {
		return organizationPolicyRepository.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElseThrow(() -> new IllegalStateException("기관에 활성 운영 정책이 없습니다: " + organizationId));
	}

	private OperationSettingResponse toResponse(Organization organization, OrganizationPolicy policy) {
		return new OperationSettingResponse(
				organization.getOrgId(),
				organization.getStatus(),
				policy.getMonthlyAiBudget(),
				policy.getRetentionDays(),
				policy.getDefaultDisclosureScope()
		);
	}

	private record UsageGroupKey(AiUsage.FeatureCode featureCode, AiModel model) {
	}
}
