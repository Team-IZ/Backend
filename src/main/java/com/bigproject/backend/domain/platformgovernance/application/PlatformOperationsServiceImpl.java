package com.bigproject.backend.domain.platformgovernance.application;

import com.bigproject.backend.domain.platformgovernance.domain.AiModel;
import com.bigproject.backend.domain.platformgovernance.domain.OrganizationGradingCalibration;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformAiTierModelPolicy;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformGradingCalibrationVersion;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformGradingModelPolicy;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformSuperAdminRepository;
import com.bigproject.backend.domain.platformgovernance.infrastructure.AiModelRepository;
import com.bigproject.backend.domain.platformgovernance.infrastructure.OrganizationGradingCalibrationRepository;
import com.bigproject.backend.domain.platformgovernance.infrastructure.PlatformAiTierModelPolicyRepository;
import com.bigproject.backend.domain.platformgovernance.infrastructure.PlatformGradingCalibrationVersionRepository;
import com.bigproject.backend.domain.platformgovernance.infrastructure.PlatformGradingModelPolicyRepository;
import com.bigproject.backend.domain.member.application.InvitationConflictException;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.InviteSuperAdminRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.InviteSuperAdminResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.PlatformModelSettingResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.SuperAdminListResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateGradingModelRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateModelPricingRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateTierModelRequest;
import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformOperationsServiceImpl implements PlatformOperationsService {

	private static final int RATE_SCALE = 4;
	/** 화면·요청이 쓰는 단가 기준 토큰 수. ai_model.price_unit_token_count의 DB DEFAULT와 같다. */
	private static final int PRICE_UNIT_TOKENS = 1_000_000;

	private final PlatformGradingModelPolicyRepository gradingModelPolicyRepository;
	private final PlatformAiTierModelPolicyRepository tierModelPolicyRepository;
	private final PlatformGradingCalibrationVersionRepository calibrationVersionRepository;
	private final OrganizationGradingCalibrationRepository organizationCalibrationRepository;
	private final AiModelRepository aiModelRepository;
	private final PlatformSuperAdminRepository superAdminRepository;
	private final OrganizationRepository organizationRepository;
	private final MemberInvitationService memberInvitationService;

	// ─────────────────────────── 모델 · 단가 탭 ───────────────────────────

	@Override
	public PlatformModelSettingResponse findModelSettings() {
		return buildModelSettings();
	}

	@Override
	@Transactional
	public PlatformModelSettingResponse updateGradingModel(UpdateGradingModelRequest request, UUID requesterId) {
		assertModelUsable(request.modelId());

		if (calibrationVersionRepository.existsByVersionCode(request.calibrationVersionCode())) {
			throw new OrganizationException(OrganizationErrorCode.CALIBRATION_VERSION_CODE_TAKEN);
		}

		/*
		 * 재캘리브레이션이 이미 돌고 있으면 겹쳐 시작하지 않는다.
		 * 두 버전이 동시에 진행되면 어느 쪽이 결과 비교의 기준인지 알 수 없어진다
		 * (목업: "버전이 다른 결과끼리는 화면에서 비교를 막는다" — 기준이 하나여야 성립하는 규칙이다).
		 */
		boolean inProgress = !calibrationVersionRepository.findByStatusIn(List.of(
				PlatformGradingCalibrationVersion.Status.PENDING,
				PlatformGradingCalibrationVersion.Status.RUNNING
		)).isEmpty();
		if (inProgress) {
			throw new OrganizationException(OrganizationErrorCode.CALIBRATION_IN_PROGRESS);
		}

		/*
		 * 정책은 append-only 버전 이력이다. 현재 활성 버전을 SUPERSEDED로 닫고 새 버전을 INSERT한다.
		 * Hibernate가 같은 flush에서 INSERT를 UPDATE보다 먼저 내보내면 부분 UNIQUE 인덱스
		 * (uq_platform_grading_model_policy_active)를 위반하므로 saveAndFlush로 UPDATE를 먼저 커밋한다.
		 */
		Optional<PlatformGradingModelPolicy> current =
				gradingModelPolicyRepository.findByStatus(PlatformGradingModelPolicy.Status.ACTIVE);
		current.ifPresent(policy -> {
			policy.supersede();
			gradingModelPolicyRepository.saveAndFlush(policy);
		});

		int previousVersion = gradingModelPolicyRepository.findFirstByOrderByPolicyVersionDesc()
				.map(PlatformGradingModelPolicy::getPolicyVersion)
				.orElse(0);

		PlatformGradingModelPolicy next = gradingModelPolicyRepository.save(
				PlatformGradingModelPolicy.createNextVersion(
						previousVersion, request.modelId(), requesterId, request.changeReason()
				)
		);

		// 채점 모델이 바뀌면 전 기관 재캘리브레이션이 필요하다 — 버전을 만들고 기관마다 대기 행을 남긴다.
		PlatformGradingCalibrationVersion calibration = calibrationVersionRepository.save(
				PlatformGradingCalibrationVersion.create(
						next.getGradingPolicyId(), request.calibrationVersionCode(), requesterId
				)
		);

		List<UUID> targetOrgIds = organizationRepository.findAliveOrganizationIds();
		organizationCalibrationRepository.saveAll(targetOrgIds.stream()
				.map(orgId -> OrganizationGradingCalibration.createPending(
						calibration.getCalibrationVersionId(), orgId
				))
				.toList());

		return buildModelSettings();
	}

	@Override
	@Transactional
	public PlatformModelSettingResponse updateTierModel(UpdateTierModelRequest request, UUID requesterId) {
		assertModelUsable(request.modelId());

		tierModelPolicyRepository
				.findByFeatureCodeAndTierCodeAndStatus(
						request.featureCode(), request.tierCode(), PlatformGradingModelPolicy.Status.ACTIVE
				)
				.ifPresent(policy -> {
					policy.supersede();
					tierModelPolicyRepository.saveAndFlush(policy);
				});

		int previousVersion = tierModelPolicyRepository
				.findFirstByFeatureCodeAndTierCodeOrderByPolicyVersionDesc(request.featureCode(), request.tierCode())
				.map(PlatformAiTierModelPolicy::getPolicyVersion)
				.orElse(0);

		tierModelPolicyRepository.save(PlatformAiTierModelPolicy.createNextVersion(
				request.featureCode(),
				request.tierCode(),
				request.modelId(),
				previousVersion,
				requesterId,
				request.changeReason()
		));

		return buildModelSettings();
	}

	@Override
	@Transactional
	public PlatformModelSettingResponse updateModelPricing(
			UUID modelId, UpdateModelPricingRequest request, UUID requesterId
	) {
		AiModel model = aiModelRepository.findById(modelId)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.AI_MODEL_NOT_AVAILABLE, "AI 모델을 찾을 수 없습니다: " + modelId
				));

		int unitTokens = request.priceUnitTokenCount() == null ? PRICE_UNIT_TOKENS : request.priceUnitTokenCount();

		// 화면은 100만 토큰당 단가를 다루고 DB는 기준 토큰 수당 단가를 저장하므로 여기서 환산한다.
		model.applyPricing(
				toUnitPrice(request.inputPricePerMillionTokens(), unitTokens),
				toUnitPrice(request.outputPricePerMillionTokens(), unitTokens),
				toUnitPrice(request.cachedInputPricePerMillionTokens(), unitTokens),
				unitTokens,
				requesterId
		);
		aiModelRepository.save(model);

		return buildModelSettings();
	}

	/** 100만 토큰당 단가 → 기준 토큰 수당 단가. null이면 단가 미설정이라 그대로 null을 넘긴다. */
	private BigDecimal toUnitPrice(BigDecimal perMillion, int unitTokens) {
		if (perMillion == null) {
			return null;
		}
		return perMillion
				.multiply(BigDecimal.valueOf(unitTokens))
				.divide(BigDecimal.valueOf(PRICE_UNIT_TOKENS), 6, RoundingMode.HALF_UP);
	}

	/** 존재하지 않거나 INACTIVE인 모델은 정책·티어에 지정할 수 없다. */
	private void assertModelUsable(UUID modelId) {
		AiModel model = aiModelRepository.findById(modelId)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.AI_MODEL_NOT_AVAILABLE, "AI 모델을 찾을 수 없습니다: " + modelId
				));
		if (model.getStatus() != AiModel.Status.ACTIVE) {
			throw new OrganizationException(
					OrganizationErrorCode.AI_MODEL_NOT_AVAILABLE,
					"비활성 모델은 정책에 지정할 수 없습니다: " + model.getModelCode()
			);
		}
	}

	private PlatformModelSettingResponse buildModelSettings() {
		List<AiModel> models = aiModelRepository.findAllByOrderByProviderAscModelCodeAsc();
		Map<UUID, AiModel> modelById = models.stream()
				.collect(Collectors.toMap(AiModel::getModelId, Function.identity()));

		return new PlatformModelSettingResponse(
				gradingModelPolicyRepository.findByStatus(PlatformGradingModelPolicy.Status.ACTIVE)
						.map(policy -> toGradingPolicy(policy, modelById))
						.orElse(null),
				tierModelPolicyRepository.findByStatus(PlatformGradingModelPolicy.Status.ACTIVE).stream()
						.map(policy -> toTierMapping(policy, modelById))
						.toList(),
				models.stream().map(this::toModelPricing).toList()
		);
	}

	private PlatformModelSettingResponse.GradingPolicy toGradingPolicy(
			PlatformGradingModelPolicy policy, Map<UUID, AiModel> modelById
	) {
		AiModel model = modelById.get(policy.getModelId());
		return new PlatformModelSettingResponse.GradingPolicy(
				policy.getGradingPolicyId(),
				policy.getPolicyVersion(),
				policy.getModelId(),
				model == null ? null : model.getDisplayName(),
				model == null ? null : model.getModelCode(),
				policy.getEffectiveFrom(),
				policy.getChangeReason(),
				calibrationVersionRepository.findByStatus(PlatformGradingCalibrationVersion.Status.ACTIVE)
						.map(this::toCalibrationSummary)
						.orElse(null),
				firstInProgressCalibration().map(this::toCalibrationSummary).orElse(null)
		);
	}

	/** 진행 중(PENDING·RUNNING) 캘리브레이션. 동시 진행을 막으므로 최대 1건이다. */
	private Optional<PlatformGradingCalibrationVersion> firstInProgressCalibration() {
		return calibrationVersionRepository.findByStatusIn(List.of(
				PlatformGradingCalibrationVersion.Status.PENDING,
				PlatformGradingCalibrationVersion.Status.RUNNING
		)).stream().findFirst();
	}

	private PlatformModelSettingResponse.CalibrationSummary toCalibrationSummary(
			PlatformGradingCalibrationVersion version
	) {
		Map<OrganizationGradingCalibration.Status, Integer> counts =
				new EnumMap<>(OrganizationGradingCalibration.Status.class);
		for (Object[] row : organizationCalibrationRepository.countByStatus(version.getCalibrationVersionId())) {
			counts.put((OrganizationGradingCalibration.Status) row[0], ((Number) row[1]).intValue());
		}

		int pending = counts.getOrDefault(OrganizationGradingCalibration.Status.PENDING, 0);
		int running = counts.getOrDefault(OrganizationGradingCalibration.Status.RUNNING, 0);
		int succeeded = counts.getOrDefault(OrganizationGradingCalibration.Status.SUCCEEDED, 0);
		int failed = counts.getOrDefault(OrganizationGradingCalibration.Status.FAILED, 0);
		int total = pending + running + succeeded + failed;

		return new PlatformModelSettingResponse.CalibrationSummary(
				version.getCalibrationVersionId(),
				version.getVersionCode(),
				version.getStatus().name(),
				version.getStartedAt(),
				version.getCompletedAt(),
				new PlatformModelSettingResponse.CalibrationProgress(
						total, pending, running, succeeded, failed,
						total == 0 ? null : BigDecimal.valueOf(succeeded)
								.divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP)
				)
		);
	}

	private PlatformModelSettingResponse.TierMapping toTierMapping(
			PlatformAiTierModelPolicy policy, Map<UUID, AiModel> modelById
	) {
		AiModel model = modelById.get(policy.getModelId());
		return new PlatformModelSettingResponse.TierMapping(
				policy.getTierPolicyId(),
				policy.getFeatureCode().name(),
				policy.getTierCode(),
				policy.getModelId(),
				model == null ? null : model.getDisplayName(),
				model == null ? null : model.getModelCode(),
				policy.getPolicyVersion(),
				policy.getEffectiveFrom()
		);
	}

	private PlatformModelSettingResponse.ModelPricing toModelPricing(AiModel model) {
		return new PlatformModelSettingResponse.ModelPricing(
				model.getModelId(),
				model.getModelCode(),
				model.getDisplayName(),
				model.getProvider(),
				model.getStatus().name(),
				model.inputPricePerMillionTokens(),
				model.outputPricePerMillionTokens(),
				model.cachedInputPricePerMillionTokens(),
				model.getCurrencyCode(),
				!model.hasPricing(),
				model.getPriceEffectiveFrom(),
				model.getPriceUpdatedAt()
		);
	}

	// ─────────────────────────── 슈퍼어드민 계정 탭 ───────────────────────────

	@Override
	public SuperAdminListResponse findSuperAdmins() {
		return buildSuperAdminList();
	}

	@Override
	@Transactional
	public InviteSuperAdminResponse inviteSuperAdmin(
			InviteSuperAdminRequest request, String actorEmail, String requestId
	) {
		InviteManagerResponse invited;
		try {
			invited = memberInvitationService.inviteSuperAdmin(request.email(), actorEmail, requestId);
		} catch (InvitationConflictException exception) {
			// 목업 케이스 계약은 SA-03 계정 탭 기준이라 member 도메인의 실패를 이 도메인 코드로 다시 던진다.
			throw new OrganizationException(OrganizationErrorCode.ALREADY_INVITED, exception.getMessage(), exception);
		} catch (ResponseStatusException exception) {
			// member 도메인은 메일 발송 실패를 502로 올린다. 오퍼레이터 초대와 같은 코드로 맞춘다 —
			// 서버 사정이 다를 뿐 사용자가 할 일은 `재발송` 하나이기 때문이다.
			if (exception.getStatusCode() == HttpStatus.BAD_GATEWAY) {
				throw new OrganizationException(
						OrganizationErrorCode.INVITE_MAIL_FAILED,
						OrganizationErrorCode.INVITE_MAIL_FAILED.defaultMessage(),
						exception
				);
			}
			throw exception;
		}

		return new InviteSuperAdminResponse(
				invited.memberId(),
				invited.email(),
				OperatorAccountStatus.PENDING,
				invited.invitedAt(),
				buildSuperAdminList()
		);
	}

	@Override
	@Transactional
	public SuperAdminListResponse updateSuperAdminStatus(UUID memberId, OperatorAccountStatus status) {
		PlatformSuperAdminRepository.SuperAdminAccount target = superAdminRepository.findSuperAdmin(memberId)
				.orElseThrow(() -> new OrganizationException(OrganizationErrorCode.SUPER_ADMIN_NOT_FOUND));

		/*
		 * 마지막 활성 슈퍼어드민은 정지할 수 없다. 정지하면 플랫폼에 들어갈 사람이 아무도 없어지고,
		 * 오퍼레이터와 달리 풀어 줄 상위 권한이 아예 없다(목업 SA-03: "이쪽은 더 치명적이다").
		 * 이미 활성이 아닌 계정을 정지하는 것은 활성 수를 줄이지 않으므로 차단 대상이 아니다.
		 */
		if (status == OperatorAccountStatus.INACTIVE
				&& target.status() == OperatorAccountStatus.ACTIVE
				&& superAdminRepository.countActiveSuperAdmins() <= 1) {
			throw new OrganizationException(
					OrganizationErrorCode.LAST_SUPER_ADMIN,
					"이 플랫폼의 마지막 슈퍼어드민입니다. 정지하면 플랫폼에 들어갈 수 있는 사람이 없어집니다. "
							+ "다른 슈퍼어드민을 먼저 활성화한 뒤에 정지할 수 있습니다."
			);
		}

		superAdminRepository.updateStatus(memberId, status);
		return buildSuperAdminList();
	}

	private SuperAdminListResponse buildSuperAdminList() {
		int activeCount = superAdminRepository.countActiveSuperAdmins();
		List<SuperAdminListResponse.SuperAdmin> content = superAdminRepository.findSuperAdmins().stream()
				.map(account -> new SuperAdminListResponse.SuperAdmin(
						account.memberId(),
						account.name(),
						account.email(),
						account.status(),
						account.lastLoginAt(),
						account.createdAt(),
						// 활성 1명뿐이면 그 계정은 정지 불가
						!(account.status() == OperatorAccountStatus.ACTIVE && activeCount <= 1)
				))
				.toList();
		return new SuperAdminListResponse(content, activeCount);
	}
}
