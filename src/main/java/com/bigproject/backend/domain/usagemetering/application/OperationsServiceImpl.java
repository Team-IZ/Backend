package com.bigproject.backend.domain.usagemetering.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.platformgovernance.domain.AiModel;
import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.platformgovernance.infrastructure.AiModelRepository;
import com.bigproject.backend.domain.usagemetering.domain.AiUsage;
import com.bigproject.backend.domain.usagemetering.domain.CohortCostRepository;
import com.bigproject.backend.domain.usagemetering.domain.OperationsActivityRepository;
import com.bigproject.backend.domain.usagemetering.domain.OperationsCostRepository;
import com.bigproject.backend.domain.usagemetering.domain.OrganizationUsageSnapshot;
import com.bigproject.backend.domain.usagemetering.domain.StorageUsageSnapshot;
import com.bigproject.backend.domain.usagemetering.infrastructure.AiUsageRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.OrganizationUsageSnapshotRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.StorageUsageSnapshotRepository;
import com.bigproject.backend.domain.usagemetering.presentation.dto.CohortCostResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.global.json.PatchField;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 조회 트랜잭션. 쓰기가 필요한 메서드에만 @Transactional을 개별로 얹는다.
public class OperationsServiceImpl implements OperationsService {

	private static final int RATE_SCALE = 4;
	private static final int COST_SCALE = 6;

	// organization 도메인의 리포지토리를 그대로 재사용한다: 기관 존재 검증, organization.status 조회(운영 설정 응답에 필요),
	// 그리고 organization_policy 조회/버전 발급까지 모두 organization 도메인의 엔티티·리포지토리로 처리한다.
	// (operations 도메인에 OrganizationPolicy를 별도로 두면 Spring Data JPA가 리포지토리 빈을 패키지 무관 simple name으로
	//  등록하기 때문에 organization 도메인의 동명 리포지토리와 빈 이름이 충돌한다.)
	private final OrganizationRepository organizationRepository;
	private final OrganizationPolicyRepository organizationPolicyRepository;
	private final OrganizationStatsRepository organizationStatsRepository;
	private final StorageUsageSnapshotRepository storageUsageSnapshotRepository;
	private final OrganizationUsageSnapshotRepository organizationUsageSnapshotRepository;
	private final AiUsageRepository aiUsageRepository;
	private final AiModelRepository aiModelRepository;
	private final OperationsCostRepository operationsCostRepository;
	private final OperationsActivityRepository operationsActivityRepository;
	private final CohortCostRepository cohortCostRepository;
	private final CurrentUserResolver currentUserResolver;

	@Override
	public OrganizationUsageResponse findUsage(UUID organizationId, YearMonth period, UUID cohortId) {
		assertOrganizationExists(organizationId);
		assertUsageAccessible(organizationId);

		Instant from = startOf(period);
		Instant to = startOf(period.plusMonths(1));
		Instant previousFrom = startOf(period.minusMonths(1));

		/*
		 * v06부터 기간 집계는 organization_usage_snapshot을 우선 조회한다.
		 * 최신 스냅샷이 실패(FAILED)면 값이 신뢰할 수 없으므로 0으로 그리지 않고 영역 전체를 실패로 알린다
		 * (목업 SA-02 case 6: "안 쓴 것과 못 읽은 것은 다르다").
		 * 스냅샷이 아예 없으면 — 수집 배치가 없는 환경 — ai_usage를 그 자리에서 집계하는 LIVE 경로로 떨어진다.
		 */
		Optional<OrganizationUsageSnapshot> snapshot =
				organizationUsageSnapshotRepository.findLatestByPeriod(organizationId, from, to);
		snapshot.filter(found -> !found.isSucceeded()).ifPresent(failed -> {
			throw new OrganizationException(
					OrganizationErrorCode.USAGE_UNAVAILABLE,
					"기간 사용량 집계가 실패한 상태입니다: " + failed.getFailureCode()
			);
		});

		OrganizationPolicy policy = organizationPolicyRepository
				.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElse(null);

		return new OrganizationUsageResponse(
				organizationId,
				period,
				resolveCurrencyCode(snapshot, policy),
				snapshot.isPresent()
						? OrganizationUsageResponse.AggregationSource.SNAPSHOT
						: OrganizationUsageResponse.AggregationSource.LIVE,
				resolveStorageUsage(organizationId, from, to, previousFrom),
				resolveActivityUsage(organizationId, from, to, snapshot),
				resolveAiCostUsage(organizationId, from, to, previousFrom, policy, snapshot),
				resolveCohortCosts(organizationId, cohortId, from, to),
				resolveClassCosts(organizationId, cohortId, from, to)
		);
	}

	private String resolveCurrencyCode(Optional<OrganizationUsageSnapshot> snapshot, OrganizationPolicy policy) {
		return snapshot.map(OrganizationUsageSnapshot::getCurrencyCode)
				.orElseGet(() -> policy == null ? null : policy.getCurrencyCode());
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

		// soft-delete된 기관을 changeStatus(ACTIVE/SUSPENDED)로 되돌리면 DB CHECK(ck_organization_status_timeline)를 위반한다:
		// ACTIVE/SUSPENDED는 deletion_requested_at·deleted_at이 NULL이어야 하는데 삭제된 기관은 이미 값이 채워져 있다.
		if (organization.getStatus() == OrganizationStatus.DELETED) {
			throw new OrganizationException(OrganizationErrorCode.ORG_ALREADY_DELETED, "삭제된 기관은 운영 설정을 변경할 수 없습니다.");
		}

		OrganizationPolicy currentPolicy = getActivePolicyOrThrow(organizationId);
		OrganizationPolicy.Settings current = currentPolicy.toSettings();
		OrganizationPolicy.Settings merged = merge(current, request);

		/*
		 * 값이 그대로면 버전을 올리지 않는다. 부분 수정이라 "모달을 열었다가 바꾸지 않고 저장"이
		 * 정상 경로로 들어오는데, 그때마다 버전이 하나씩 쌓이면 이력에 원인 없는 행이 섞여
		 * 정작 무엇이 언제 바뀌었는지 읽기 어려워진다. 기관 상태만 바꾸는 요청도 마찬가지다 —
		 * 상태는 organization의 값이라 정책 버전과 무관하다.
		 */
		OrganizationPolicy effectivePolicy = currentPolicy;
		if (!isSameSettings(current, merged)) {
			// organization_policy는 append-only 이력 테이블이므로 기존 행을 고치지 않고,
			// 현재 활성 버전은 SUPERSEDED로 닫은 뒤 새 버전을 INSERT한다.
			// Hibernate는 같은 flush 안에서 INSERT를 UPDATE보다 먼저 실행하기 때문에, supersede()만 호출하고
			// 넘어가면 새 버전 INSERT가 먼저 나가면서 부분 유니크 인덱스(uq_organization_policy_current)를
			// 위반한다. saveAndFlush로 UPDATE를 먼저 커밋해 순서를 강제한다.
			currentPolicy.supersede(requesterId);
			organizationPolicyRepository.saveAndFlush(currentPolicy);

			effectivePolicy = organizationPolicyRepository.save(
					OrganizationPolicy.createNextVersion(currentPolicy, merged, requesterId)
			);
		}

		// 상태는 보냈을 때만 바꾼다. 안 보낸 필드를 유지한다는 규칙이 정책값과 기관 상태에 같이 적용된다.
		if (request.organizationStatus() != null) {
			organization.changeStatus(request.organizationStatus());
		}
		organization.touchUpdatedBy(requesterId);

		return toResponse(organization, effectivePolicy);
	}

	/**
	 * 부분 수정 병합. <b>보내지 않은 필드는 직전 활성 버전의 값을 그대로 승계한다.</b>
	 *
	 * <p>상한 두 개만 {@link PatchField}로 받는다 — {@code null}이 "값 없음"이 아니라 <b>무제한</b>이라는
	 * 값이라, 안 보낸 것과 구분하지 못하면 한 번 건 상한을 다시 풀 방법이 사라진다.
	 * 나머지 필드는 {@code null}이 의미를 갖지 않으므로 생략과 같게 본다.
	 */
	private OrganizationPolicy.Settings merge(
			OrganizationPolicy.Settings current, UpdateOperationSettingRequest request) {
		return new OrganizationPolicy.Settings(
				requireNonNullElse(request.monthlyAiBudget(), current.monthlyAiBudget()),
				PatchField.mergeInto(request.monthlyTokenLimit(), current.monthlyTokenLimit()),
				PatchField.mergeInto(request.storageLimitBytes(), current.storageLimitBytes()),
				requireNonNullElse(request.dataRetentionDays(), current.retentionDays()),
				requireNonNullElse(request.defaultDisclosureScope(), current.defaultDisclosureScope()),
				requireNonNullElse(request.codeSessionTierCode(), current.codeSessionTierCode()),
				requireNonNullElse(request.allowManagerInvite(), current.allowManagerInvite()),
				requireNonNullElse(request.allowDataExport(), current.allowDataExport()),
				requireNonNullElse(request.allowZipSubmission(), current.allowZipSubmission()),
				requireNonNullElse(request.allowGithubIntegration(), current.allowGithubIntegration()),
				// 화면에서 빠진 항목이라 요청 필드가 없다. 정책 원장에는 남아 있으므로 직전 값을 그대로 승계한다
				// (컬럼이 NOT NULL이고, 승계하지 않으면 새 버전마다 DB 기본값으로 되돌아간다).
				current.enableBigProjectContributionAnalysis()
		);
	}

	private static <T> T requireNonNullElse(T sent, T current) {
		return sent == null ? current : sent;
	}

	/**
	 * 새 버전을 발급할 값인지 판정한다. {@code record}의 {@code equals}를 그대로 쓰지 않는 이유는
	 * {@link BigDecimal}이 <b>소수 자릿수까지 비교</b>하기 때문이다 — DB에서 읽은 {@code 1500.00}과
	 * 요청으로 들어온 {@code 1500}이 다른 값이 되어, 바꾸지 않은 예산이 매번 새 버전을 만든다.
	 */
	private boolean isSameSettings(OrganizationPolicy.Settings left, OrganizationPolicy.Settings right) {
		boolean sameBudget = left.monthlyAiBudget() == null || right.monthlyAiBudget() == null
				? left.monthlyAiBudget() == right.monthlyAiBudget()
				: left.monthlyAiBudget().compareTo(right.monthlyAiBudget()) == 0;

		return sameBudget && left.withBudget(null).equals(right.withBudget(null));
	}

	/**
	 * 사용량 조회 접근 검증. 슈퍼어드민은 모든 기관을, 오퍼레이터는 자기 기관만 볼 수 있다.
	 * 목업 OP-06 ⑤ 비용은 오퍼레이터 화면이지만 테넌트 경계를 넘어서는 안 된다.
	 */
	private void assertUsageAccessible(UUID organizationId) {
		AuthUser actor = currentUserResolver.resolveCurrentUser();
		if (actor.role() == Role.SUPER_ADMIN) {
			return;
		}
		if (!organizationId.equals(actor.organizationId())) {
			throw new OrganizationException(OrganizationErrorCode.ORG_ACCESS_DENIED, "다른 기관의 사용량은 조회할 수 없습니다.");
		}
	}

	/**
	 * storage_usage_snapshot의 카테고리별 저장량을 목업 `저장량 구성` 4개 항목에 매핑한다.
	 * CURRICULUM_PDF, DATABASE 카테고리는 별도 항목이 없어 총계에만 반영된다.
	 */
	private OrganizationUsageResponse.StorageUsage resolveStorageUsage(
			UUID organizationId, Instant from, Instant to, Instant previousFrom
	) {
		Map<StorageUsageSnapshot.StorageCategory, Long> current = latestBytesByCategory(organizationId, from, to);
		long totalBytes = totalBytes(current);
		long previousTotalBytes = totalBytes(latestBytesByCategory(organizationId, previousFrom, from));

		return new OrganizationUsageResponse.StorageUsage(
				totalBytes,
				current.getOrDefault(StorageUsageSnapshot.StorageCategory.CODE_ARTIFACT, 0L),
				current.getOrDefault(StorageUsageSnapshot.StorageCategory.SESSION_TRANSCRIPT, 0L),
				current.getOrDefault(StorageUsageSnapshot.StorageCategory.SCORE_EVIDENCE, 0L),
				current.getOrDefault(StorageUsageSnapshot.StorageCategory.REPORT_EXPORT, 0L),
				changeRate(BigDecimal.valueOf(totalBytes), BigDecimal.valueOf(previousTotalBytes))
		);
	}

	/**
	 * 저장량은 주기 스냅샷이라 기간 내 값을 단순 합산하면 수집 횟수만큼 중복 집계된다.
	 * 목업의 `저장량 구성 총 9.4 GB`는 시점 값이므로 <b>가장 최근 측정 세트 하나</b>만 쓴다.
	 *
	 * <p>v06부터는 카테고리별로 최신 행을 따로 고르지 않고 {@code measurement_batch_id}로 세트를 고른다 —
	 * 카테고리마다 다른 시점을 섞으면 총계와 세부가 서로 어긋난다.
	 */
	private Map<StorageUsageSnapshot.StorageCategory, Long> latestBytesByCategory(UUID organizationId, Instant from, Instant to) {
		Map<StorageUsageSnapshot.StorageCategory, Long> result =
				new EnumMap<>(StorageUsageSnapshot.StorageCategory.class);

		storageUsageSnapshotRepository.findLatestBatchId(organizationId, from, to).ifPresent(batchId ->
				storageUsageSnapshotRepository.findByOrgIdAndMeasurementBatchId(organizationId, batchId)
						.forEach(snapshot -> result.merge(
								snapshot.getStorageCategory(),
								snapshot.getUsedBytes() == null ? 0L : snapshot.getUsedBytes(),
								Long::sum
						))
		);
		return result;
	}

	/**
	 * 기관 총 저장량.
	 *
	 * <p>v06에 {@code ORG_TOTAL} 카테고리가 추가됐다. 원천이 총계 행을 제공하면 그것을 그대로 쓰고,
	 * 없을 때만 세부 카테고리를 합산한다 — <b>둘을 함께 더하면 저장량이 두 배로 보인다.</b>
	 */
	private long totalBytes(Map<StorageUsageSnapshot.StorageCategory, Long> bytesByCategory) {
		Long orgTotal = bytesByCategory.get(StorageUsageSnapshot.StorageCategory.ORG_TOTAL);
		if (orgTotal != null) {
			return orgTotal;
		}
		return bytesByCategory.entrySet().stream()
				.filter(entry -> entry.getKey() != StorageUsageSnapshot.StorageCategory.ORG_TOTAL)
				.mapToLong(Map.Entry::getValue)
				.sum();
	}

	/**
	 * 목업 `사용 규모` 4지표. 스냅샷이 있으면 그 값을 쓰고, 없으면 네 지표를 모두 실시간으로 센다.
	 *
	 * <p>v07에서 06_MEAS·10_RPT 테이블이 생겨 세션·채점·리포트도 LIVE 집계가 가능해졌다
	 * (이전에는 테이블이 없어 0 고정이었다).
	 */
	private OrganizationUsageResponse.ActivityUsage resolveActivityUsage(
			UUID organizationId, Instant from, Instant to, Optional<OrganizationUsageSnapshot> snapshot
	) {
		if (snapshot.isPresent()) {
			OrganizationUsageSnapshot found = snapshot.get();
			return new OrganizationUsageResponse.ActivityUsage(
					zeroIfNull(found.getActiveTraineeCount()),
					zeroIfNull(found.getCompletedSessionCount()),
					zeroIfNull(found.getGradingExecutionCount()),
					zeroIfNull(found.getPublishedReportCount())
			);
		}

		int activeTrainees = organizationStatsRepository
				.countActiveTraineesByOrgId(List.of(organizationId))
				.getOrDefault(organizationId, 0);

		return new OrganizationUsageResponse.ActivityUsage(
				activeTrainees,
				operationsActivityRepository.countCompletedSessions(organizationId, from, to),
				operationsActivityRepository.countGradingRounds(organizationId, from, to),
				operationsActivityRepository.countPublishedReports(organizationId, from, to)
		);
	}

	/**
	 * 모델별 내역은 스냅샷에 없으므로(스냅샷은 기간 총량만 보관) 항상 ai_usage에서 만든다.
	 * 총 비용·토큰 합계만 스냅샷이 있으면 그 값을 신뢰한다.
	 */
	private OrganizationUsageResponse.AiCostUsage resolveAiCostUsage(
			UUID organizationId,
			Instant from,
			Instant to,
			Instant previousFrom,
			OrganizationPolicy policy,
			Optional<OrganizationUsageSnapshot> snapshot
	) {
		List<AiUsage> usages = aiUsageRepository.findByOrgIdAndOccurredAtBetween(organizationId, from, to);

		Map<String, String> modelDisplayNames = resolveModelDisplayNames(usages);
		List<OrganizationUsageResponse.ModelUsage> models = groupByFeatureAndModel(usages).entrySet().stream()
				.map(entry -> toModelUsage(entry.getKey(), entry.getValue(), modelDisplayNames))
				.toList();

		long unpricedCallCount = snapshot
				.map(found -> zeroIfNull(found.getUnpricedCallCount()))
				.orElseGet(() -> usages.stream().filter(AiUsage::isPricingMissing).count());

		// 단가 미설정 모델은 합계에서 제외한다 — 0으로 계산하면 청구액이 실제보다 작아 보인다(목업 SA-03 `단가 미입력`).
		BigDecimal liveTotalCost = models.stream()
				.filter(model -> !model.pricingMissing())
				.map(OrganizationUsageResponse.ModelUsage::cost)
				.filter(cost -> cost != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal totalCost = snapshot
				.map(OrganizationUsageSnapshot::getEffectiveCost)
				.orElse(liveTotalCost);

		BigDecimal monthlyBudget = policy == null ? BigDecimal.ZERO : policy.getMonthlyAiBudget();
		BigDecimal previousCost = aiUsageRepository
				.findByOrgIdAndOccurredAtBetween(organizationId, previousFrom, from).stream()
				.map(AiUsage::resolveCost)
				.filter(cost -> cost != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		OrganizationUsageResponse.UsageTotal total = new OrganizationUsageResponse.UsageTotal(
				models.stream().mapToLong(OrganizationUsageResponse.ModelUsage::calls).sum(),
				models.stream().mapToLong(OrganizationUsageResponse.ModelUsage::inputTokens).sum(),
				models.stream().mapToLong(OrganizationUsageResponse.ModelUsage::outputTokens).sum(),
				totalCost
		);

		return new OrganizationUsageResponse.AiCostUsage(
				totalCost,
				monthlyBudget,
				usageRate(totalCost, monthlyBudget),
				monthlyBudget.compareTo(BigDecimal.ZERO) > 0 && totalCost.compareTo(monthlyBudget) > 0,
				changeRate(totalCost, previousCost),
				unpricedCallCount == 0,
				unpricedCallCount,
				total,
				models
		);
	}

	private List<OrganizationUsageResponse.CohortCostUsage> resolveCohortCosts(
			UUID organizationId, UUID cohortId, Instant from, Instant to
	) {
		return operationsCostRepository.findCohortCosts(organizationId, cohortId, from, to).stream()
				.map(cost -> new OrganizationUsageResponse.CohortCostUsage(
						cost.cohortId(),
						cost.name(),
						cost.traineeCount(),
						cost.cost(),
						costPerTrainee(cost.cost(), cost.traineeCount()),
						cost.unpricedCallCount()
				))
				.toList();
	}

	/** 반별 비용은 목업상 "선택 기수" 범위다. 기수를 고르지 않았으면 보여줄 표가 없다. */
	private List<OrganizationUsageResponse.ClassCostUsage> resolveClassCosts(
			UUID organizationId, UUID cohortId, Instant from, Instant to
	) {
		if (cohortId == null) {
			return List.of();
		}
		// 세션 수는 비용과 조인 경로가 완전히 달라(교육생 반 배정을 타고 내려간다) 한 쿼리에 합치면
		// 카티션 곱으로 비용이 부풀어 오른다. 반별로 따로 세어 여기서 합친다.
		Map<UUID, Long> sessionsByClass =
				operationsActivityRepository.countCompletedSessionsByClass(organizationId, cohortId, from, to);

		return operationsCostRepository.findClassCosts(organizationId, cohortId, from, to).stream()
				.map(cost -> new OrganizationUsageResponse.ClassCostUsage(
						cost.classId(),
						cost.name(),
						cost.managerName(),
						cost.traineeCount(),
						// 세션이 한 건도 없는 반은 맵에 키가 없다 — 그 반은 실제로 0이다.
						sessionsByClass.getOrDefault(cost.classId(), 0L),
						cost.cost(),
						cost.unpricedCallCount()
				))
				.toList();
	}

	/** 교육생 1인당 비용. 인원이 0이면 나눌 수 없어 null이다. */
	private BigDecimal costPerTrainee(BigDecimal cost, int traineeCount) {
		if (cost == null || traineeCount <= 0) {
			return null;
		}
		return cost.divide(BigDecimal.valueOf(traineeCount), COST_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 사용 원장에 남은 모델 코드를 모델 마스터의 표시명으로 해석한다.
	 * v07에서 ai_usage가 model_code만 복사해 두므로 표시명은 여기서 한 번에 조회한다(코드당 N+1 방지).
	 * 마스터에서 사라진 모델은 표시명이 없으므로 호출부가 코드를 그대로 노출한다.
	 */
	private Map<String, String> resolveModelDisplayNames(List<AiUsage> usages) {
		Set<String> codes = usages.stream()
				.map(AiUsage::getModelCode)
				.collect(Collectors.toSet());
		if (codes.isEmpty()) {
			return Map.of();
		}
		return aiModelRepository.findByModelCodeIn(codes).stream()
				.collect(Collectors.toMap(AiModel::getModelCode, AiModel::getDisplayName, (first, ignored) -> first));
	}

	// (기능 코드, 티어, 모델 코드) 조합별로 묶어 모델별 사용량 내역(ModelUsage)을 만든다.
	private Map<UsageGroupKey, List<AiUsage>> groupByFeatureAndModel(List<AiUsage> usages) {
		return usages.stream()
				.collect(Collectors.groupingBy(usage ->
						new UsageGroupKey(usage.getFeatureCode(), usage.getTierCode(), usage.getModelCode())));
	}

	private OrganizationUsageResponse.ModelUsage toModelUsage(
			UsageGroupKey key,
			List<AiUsage> group,
			Map<String, String> modelDisplayNames
	) {
		long calls = group.size();
		long inputTokens = group.stream().mapToLong(AiUsage::getInputTokenCount).sum();
		long outputTokens = group.stream().mapToLong(AiUsage::getOutputTokenCount).sum();

		// 그룹 전체가 단가 미설정이면 비용을 알 수 없다. 일부만 미설정이면 계산 가능한 건만 더한다.
		boolean pricingMissing = group.stream().allMatch(AiUsage::isPricingMissing);
		BigDecimal cost = pricingMissing ? null : group.stream()
				.map(AiUsage::resolveCost)
				.filter(value -> value != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		/*
		 * 단가는 호출 시점 스냅샷이 ai_usage에 복사돼 있으므로 그 값을 쓴다(모델 마스터의 현재 단가가 아니라
		 * 그때 청구된 단가여야 한다). 같은 (기능, 티어, 모델) 그룹 안에서는 단가가 같다고 보고 첫 건을 대표로 쓴다.
		 */
		AiUsage representative = group.stream()
				.filter(usage -> !usage.isPricingMissing())
				.findFirst()
				.orElse(group.get(0));

		return new OrganizationUsageResponse.ModelUsage(
				key.featureCode().name(),
				key.tierCode(),
				modelDisplayNames.getOrDefault(key.modelCode(), key.modelCode()),
				calls,
				inputTokens,
				outputTokens,
				perMillionTokens(representative.getInputUnitPrice()),
				perMillionTokens(representative.getOutputUnitPrice()),
				pricingMissing,
				cost
		);
	}

	/**
	 * 토큰당 단가를 100만 토큰당 단가로 환산한다.
	 * 환산 기준 토큰 수는 모델 마스터의 {@code price_unit_token_count}이며, 값이 없으면 100만으로 본다.
	 */
	private BigDecimal perMillionTokens(BigDecimal unitPrice) {
		if (unitPrice == null) {
			return null;
		}
		return unitPrice.multiply(BigDecimal.valueOf(1_000_000));
	}

	private void assertOrganizationExists(UUID organizationId) {
		if (!organizationRepository.existsById(organizationId)) {
			throw new OrganizationException(OrganizationErrorCode.ORG_NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId);
		}
	}

	private Organization getOrganizationOrThrow(UUID organizationId) {
		return organizationRepository.findById(organizationId)
				.orElseThrow(() -> new OrganizationException(OrganizationErrorCode.ORG_NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId));
	}

	/**
	 * 운영 설정 조회/변경은 활성 정책 자체를 다루므로 정책이 없으면 진행할 수 없다.
	 * 다만 이는 서버 결함이 아니라 데이터 상태이므로 500이 아니라 409로 알린다.
	 * (정상 생성 경로 POST /organizations를 거치지 않고 만들어진 기관에서 발생한다.)
	 */
	private OrganizationPolicy getActivePolicyOrThrow(UUID organizationId) {
		return organizationPolicyRepository.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.ORG_POLICY_NOT_FOUND,
						"기관에 활성 운영 정책이 없습니다. organization_policy에 ACTIVE 버전이 있어야 합니다: " + organizationId
				));
	}

	private Instant startOf(YearMonth period) {
		return period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	/** 예산 소진율(0~1). 예산이 0 이하면 나눌 수 없어 null을 반환한다. */
	private BigDecimal usageRate(BigDecimal cost, BigDecimal budget) {
		if (budget == null || budget.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		return cost.divide(budget, RATE_SCALE, RoundingMode.HALF_UP);
	}

	/** 전월 대비 증감률. 전월 값이 0이면 증감률이 정의되지 않으므로 null을 반환한다(화면에서는 `—`). */
	private BigDecimal changeRate(BigDecimal current, BigDecimal previous) {
		if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
			return null;
		}
		return current.subtract(previous).divide(previous, RATE_SCALE, RoundingMode.HALF_UP);
	}

	private long zeroIfNull(Long value) {
		return value == null ? 0L : value;
	}

	private int zeroIfNull(Integer value) {
		return value == null ? 0 : value;
	}

	private OperationSettingResponse toResponse(Organization organization, OrganizationPolicy policy) {
		return new OperationSettingResponse(
				organization.getOrgId(),
				organization.getStatus(),
				policy.getMonthlyAiBudget(),
				policy.getCurrencyCode(),
				policy.getMonthlyTokenLimit(),
				policy.getStorageLimitBytes(),
				policy.getRetentionDays(),
				policy.getDefaultDisclosureScope(),
				policy.getCodeSessionTierCode(),
				policy.getAllowManagerInvite(),
				policy.getAllowDataExport(),
				policy.getAllowZipSubmission(),
				policy.getAllowGithubIntegration(),
				policy.getPolicyVersion()
		);
	}

	/** (기능, 티어, 모델 코드) 조합. 티어가 호출 스냅샷으로 남아 같은 모델이라도 티어가 다르면 별도 행으로 보여준다. */
	private record UsageGroupKey(AiUsage.FeatureCode featureCode, AiTier tierCode, String modelCode) {
	}

	// ════════════════════════════════════════════════════════════════
	// OP-06 ⑤ 비용 탭
	// ════════════════════════════════════════════════════════════════

	@Override
	@Transactional(readOnly = true)
	public CohortCostResponse findCohortCost(
			UUID organizationId, UUID cohortId, CohortCostResponse.ClassCostSort sort) {

		assertOrganizationExists(organizationId);
		assertUsageAccessible(organizationId);

		CohortCostRepository.CohortPeriod cohort =
				cohortCostRepository.findCohortPeriod(organizationId, cohortId);
		if (cohort == null) {
			throw new OrganizationException(
					OrganizationErrorCode.ORG_NOT_FOUND, "기수를 찾을 수 없습니다: " + cohortId);
		}

		/*
		 * 월 범위 = 기수 시작월 ~ min(이번 달, 기수 종료월).
		 * 아직 오지 않은 달은 담지 않는다 — 기수가 9월까지여도 7월이면 다섯 칸이다.
		 */
		YearMonth firstMonth = YearMonth.from(cohort.startDate());
		YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
		YearMonth lastMonth = YearMonth.from(cohort.endDate());
		if (lastMonth.isAfter(currentMonth)) {
			lastMonth = currentMonth;
		}
		if (lastMonth.isBefore(firstMonth)) {
			lastMonth = firstMonth;
		}

		List<YearMonth> months = new ArrayList<>();
		for (YearMonth m = firstMonth; !m.isAfter(lastMonth); m = m.plusMonths(1)) {
			months.add(m);
		}

		Instant rangeFrom = startOf(firstMonth);
		Instant rangeTo = startOf(lastMonth.plusMonths(1));

		// ── 기수 월별 (비용 + 세션) ──
		Map<YearMonth, CohortCostRepository.MonthlyCohortCost> cohortByMonth =
				cohortCostRepository.findCohortMonthlyCost(organizationId, cohortId, rangeFrom, rangeTo)
						.stream()
						.collect(Collectors.toMap(
								CohortCostRepository.MonthlyCohortCost::month, r -> r, (a, b) -> a));

		Map<YearMonth, List<String>> roundNamesByMonth = new LinkedHashMap<>();
		for (CohortCostRepository.MonthlyRoundName row
				: cohortCostRepository.findCohortMonthlyRoundNames(organizationId, cohortId, rangeFrom, rangeTo)) {
			roundNamesByMonth.computeIfAbsent(row.month(), k -> new ArrayList<>()).add(row.roundName());
		}

		// 최근 달이 앞 — 월별 표는 최신이 위다(반 매트릭스와 방향이 반대).
		List<CohortCostResponse.MonthlyCost> monthly = new ArrayList<>();
		BigDecimal cohortTotal = BigDecimal.ZERO;
		for (YearMonth m : months) {
			CohortCostRepository.MonthlyCohortCost row = cohortByMonth.get(m);
			BigDecimal amount = row == null ? BigDecimal.ZERO : row.amount();
			cohortTotal = cohortTotal.add(amount);
			monthly.add(new CohortCostResponse.MonthlyCost(
					m.toString(),
					amount,
					row == null ? 0L : row.sessions(),
					roundNamesByMonth.getOrDefault(m, List.of())
			));
		}
		Collections.reverse(monthly);

		// ── 기관 전체 이번 달 / 지난달 ──
		YearMonth basisMonth = lastMonth;
		YearMonth previousMonth = basisMonth.minusMonths(1);
		Map<YearMonth, BigDecimal> orgByMonth =
				cohortCostRepository.findOrganizationMonthlyCost(
								organizationId, startOf(previousMonth), startOf(basisMonth.plusMonths(1)))
						.stream()
						.collect(Collectors.toMap(
								CohortCostRepository.MonthlyAmount::month,
								CohortCostRepository.MonthlyAmount::amount, (a, b) -> a));

		BigDecimal total = orgByMonth.getOrDefault(basisMonth, BigDecimal.ZERO);
		// 지난달 행이 아예 없으면 "첫 달"이라 null이다 — 0과 구분해야 화면이 `—`를 그린다.
		BigDecimal previousTotal = orgByMonth.get(previousMonth);
		BigDecimal changePct = changePercent(total, previousTotal);

		// ── 기수 카드 (기준 월에 실제로 비용이 난 기수만) ──
		List<CohortCostResponse.CohortCard> cards =
				cohortCostRepository.findActiveCohortCards(
								organizationId, startOf(basisMonth), startOf(basisMonth.plusMonths(1)))
						.stream()
						.map(c -> new CohortCostResponse.CohortCard(
								c.cohortId(), c.name(), c.amount(), c.traineeCount(),
								periodLabel(c.startDate(), c.endDate())))
						.toList();

		// ── 반별 ──
		Map<UUID, Map<YearMonth, BigDecimal>> classMonthly = new HashMap<>();
		for (CohortCostRepository.MonthlyClassAmount row
				: cohortCostRepository.findClassMonthlyCost(organizationId, cohortId, rangeFrom, rangeTo)) {
			classMonthly.computeIfAbsent(row.classId(), k -> new HashMap<>()).put(row.month(), row.amount());
		}

		List<YearMonth> ascendingMonths = months;   // 매트릭스는 왼쪽에서 오른쪽으로 시간이 흐른다
		List<CohortCostResponse.ClassCost> classes = new ArrayList<>();
		for (CohortCostRepository.ClassSummary cs
				: cohortCostRepository.findClassSummaries(organizationId, cohortId, rangeFrom, rangeTo)) {
			Map<YearMonth, BigDecimal> byMonth = classMonthly.getOrDefault(cs.classId(), Map.of());
			List<CohortCostResponse.MonthlyClassCost> cells = ascendingMonths.stream()
					.map(m -> new CohortCostResponse.MonthlyClassCost(
							m.toString(), byMonth.getOrDefault(m, BigDecimal.ZERO)))
					.toList();
			classes.add(new CohortCostResponse.ClassCost(
					cs.classId(), cohortId, cs.name(), cs.managerName(),
					cells, cs.cohortAmount(), cs.cohortSessions()));
		}

		if (sort == CohortCostResponse.ClassCostSort.COHORT_AMOUNT) {
			classes.sort(Comparator.comparing(
					CohortCostResponse.ClassCost::cohortAmount).reversed());
		}
		// NAME은 조회 쿼리가 이미 이름순으로 준다.

		// ── 예산 ──
		OrganizationPolicy policy = organizationPolicyRepository
				.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElse(null);
		BigDecimal budget = cohortBudget(policy, cohort.startDate(), cohort.endDate());

		int monthsLeft = (int) Math.max(0, ChronoUnit.MONTHS.between(basisMonth, YearMonth.from(cohort.endDate())));

		CohortCostResponse.CostSummary summary = new CohortCostResponse.CostSummary(
				basisMonth.toString(), total, previousTotal, changePct,
				cohortTotal, budget, monthsLeft, monthly, cards);

		return new CohortCostResponse(organizationId, cohortId, OrganizationPolicy.PLATFORM_CURRENCY_CODE, summary, classes);
	}

	/**
	 * 전월 대비 증감률을 <b>퍼센트</b>로 낸다(`+12.0`). 화면 계약이 퍼센트라 0~1 비율을 쓰는
	 * 다른 응답과 단위가 다르다. 지난달이 없거나 0이면 나눌 수 없어 0을 준다 —
	 * 이 자리는 화면이 부호와 함께 그대로 찍는 값이라 null을 주면 렌더가 깨진다.
	 */
	private BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
		if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return current.subtract(previous)
				.multiply(BigDecimal.valueOf(100))
				.divide(previous, 1, RoundingMode.HALF_UP);
	}

	/**
	 * 기수 전체 예산 = 월 예산 × 기수 개월 수.
	 *
	 * <p>기수 단위 예산 컬럼이 스키마에 없어 파생한다. 활성 정책이 없거나 월 예산이 0이면
	 * null을 주고, 화면은 그때 비율을 그리지 않는다 — 분모 없는 퍼센트는 만들 수 없다.
	 */
	private BigDecimal cohortBudget(OrganizationPolicy policy, LocalDate start, LocalDate end) {
		if (policy == null || policy.getMonthlyAiBudget() == null
				|| policy.getMonthlyAiBudget().compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		long spanMonths = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1;
		return policy.getMonthlyAiBudget().multiply(BigDecimal.valueOf(Math.max(1, spanMonths)));
	}

	/** `2026-03 ~ 09`. 해가 넘어가면 뒤쪽도 연도를 붙인다 — `2026-11 ~ 2027-02`. */
	private String periodLabel(LocalDate start, LocalDate end) {
		String head = YearMonth.from(start).toString();
		YearMonth tail = YearMonth.from(end);
		return start.getYear() == end.getYear()
				? head + " ~ " + String.format("%02d", tail.getMonthValue())
				: head + " ~ " + tail;
	}
}
