package com.bigproject.backend.domain.usagemetering.application;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.platformgovernance.infrastructure.AiModelRepository;
import com.bigproject.backend.domain.usagemetering.domain.CohortCostRepository;
import com.bigproject.backend.domain.usagemetering.domain.OperationsActivityRepository;
import com.bigproject.backend.domain.usagemetering.domain.OperationsCostRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.AiUsageRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.OrganizationUsageSnapshotRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.StorageUsageSnapshotRepository;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import com.bigproject.backend.domain.usagemetering.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.global.json.PatchField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 운영 설정 <b>부분 수정</b>의 병합 규칙.
 *
 * <p>설정 탭이 항목별 모달이라 한 번에 오는 값은 2~4개다. 전체 치환이던 시절에는 나머지를 화면이
 * 열릴 때 받아 둔 값으로 다시 실어 보냈고, 그 사이 다른 사람이 바꾼 값이 조용히 되돌아갔다.
 * <b>보내지 않은 필드를 건드리지 않는 것</b>이 이 테스트가 지키는 계약이다.
 */
class OperationSettingPatchTest {

	private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
	private final OrganizationPolicyRepository policyRepository = mock(OrganizationPolicyRepository.class);

	private OperationsService service;

	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID ACTOR_ID = UUID.randomUUID();

	private Organization organization;
	private OrganizationPolicy currentPolicy;

	/** 현재 활성 정책. 상한 둘은 값이 걸려 있고, 나머지는 아래 검증의 기준값이다. */
	private static final OrganizationPolicy.Settings CURRENT = new OrganizationPolicy.Settings(
			new BigDecimal("1000.00"),
			200_000_000L,
			107_374_182_400L,
			180,
			DisclosureScope.SUMMARY,
			AiTier.BALANCED,
			true, true, true, true, true
	);

	@BeforeEach
	void setUp() {
		organization = Organization.create("그린대학교", "그린대학교", ACTOR_ID);
		currentPolicy = OrganizationPolicy.createInitial(ORG_ID, CURRENT, ACTOR_ID);

		when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
		when(policyRepository.findByOrgIdAndStatus(ORG_ID, OrganizationPolicy.Status.ACTIVE))
				.thenReturn(Optional.of(currentPolicy));
		when(policyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service = newService();
	}

	@Test
	void 보내지_않은_필드는_직전_버전_값을_승계한다() {
		// 예산 모달이 담당하는 값 하나만 온다.
		service.updateSettings(ORG_ID, patch().monthlyAiBudget(new BigDecimal("1500.00")).build(), ACTOR_ID);

		OrganizationPolicy saved = capturePolicy();
		OrganizationPolicy.Settings settings = saved.toSettings();

		assertThat(settings.monthlyAiBudget()).isEqualByComparingTo("1500.00");
		// 나머지는 손대지 않는다 — 여기가 무너지면 다른 사람이 켠 설정이 조용히 되돌아간다.
		assertThat(settings.allowDataExport()).isTrue();
		assertThat(settings.defaultDisclosureScope()).isEqualTo(DisclosureScope.SUMMARY);
		assertThat(settings.retentionDays()).isEqualTo(180);
		assertThat(settings.monthlyTokenLimit()).isEqualTo(200_000_000L);
	}

	@Test
	void 상한에_null을_보내면_무제한으로_푼다() {
		service.updateSettings(ORG_ID, patch().monthlyTokenLimit(PatchField.of(null)).build(), ACTOR_ID);

		// null은 "안 보냈다"가 아니라 무제한이라는 값이다. 구분하지 못하면 한 번 건 상한을 풀 수 없다.
		assertThat(capturePolicy().toSettings().monthlyTokenLimit()).isNull();
	}

	@Test
	void 상한을_보내지_않으면_지금_값을_유지한다() {
		service.updateSettings(ORG_ID, patch().allowDataExport(false).build(), ACTOR_ID);

		assertThat(capturePolicy().toSettings().monthlyTokenLimit()).isEqualTo(200_000_000L);
		assertThat(capturePolicy().toSettings().storageLimitBytes()).isEqualTo(107_374_182_400L);
	}

	@Test
	void 값이_그대로면_새_버전을_만들지_않는다() {
		// 모달을 열었다가 바꾸지 않고 저장한 경우. 버전만 올라가면 이력에 원인 없는 행이 쌓인다.
		OperationSettingResponse response = service.updateSettings(
				ORG_ID, patch().allowDataExport(true).monthlyAiBudget(new BigDecimal("1000")).build(), ACTOR_ID);

		verify(policyRepository, never()).save(any());
		assertThat(response.policyVersion()).isEqualTo(1);
	}

	@Test
	void 기관_상태만_보내면_정책_버전을_올리지_않는다() {
		service.updateSettings(ORG_ID, patch().organizationStatus(OrganizationStatus.SUSPENDED).build(), ACTOR_ID);

		// 상태는 정책이 아니라 기관의 값이다.
		verify(policyRepository, never()).save(any());
		assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);
	}

	@Test
	void 상태를_보내지_않으면_기관_상태를_건드리지_않는다() {
		organization.changeStatus(OrganizationStatus.SUSPENDED);

		service.updateSettings(ORG_ID, patch().allowDataExport(false).build(), ACTOR_ID);

		assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);
	}

	@Test
	void 값이_바뀌면_활성_버전을_닫고_새_버전을_발급한다() {
		service.updateSettings(ORG_ID, patch().allowZipSubmission(false).build(), ACTOR_ID);

		assertThat(currentPolicy.getStatus()).isEqualTo(OrganizationPolicy.Status.SUPERSEDED);
		assertThat(capturePolicy().getPolicyVersion()).isEqualTo(2);
		assertThat(capturePolicy().toSettings().allowZipSubmission()).isFalse();
	}

	/**
	 * 화면에서 빠진 항목이라 요청 필드가 없다. 정책 원장의 컬럼은 NOT NULL이라 새 버전에도 값이
	 * 들어가야 하는데, 승계하지 않으면 버전이 올라갈 때마다 DB 기본값으로 되돌아간다.
	 */
	@Test
	void 요청에서_빠진_기여도_분석_값은_직전_버전에서_승계한다() {
		currentPolicy = OrganizationPolicy.createInitial(
				ORG_ID, CURRENT.withBudget(new BigDecimal("1000.00")), ACTOR_ID);
		when(policyRepository.findByOrgIdAndStatus(ORG_ID, OrganizationPolicy.Status.ACTIVE))
				.thenReturn(Optional.of(currentPolicy));

		service.updateSettings(ORG_ID, patch().allowManagerInvite(false).build(), ACTOR_ID);

		assertThat(capturePolicy().toSettings().enableBigProjectContributionAnalysis()).isTrue();
	}

	private OrganizationPolicy capturePolicy() {
		ArgumentCaptor<OrganizationPolicy> captor = ArgumentCaptor.forClass(OrganizationPolicy.class);
		verify(policyRepository).save(captor.capture());
		return captor.getValue();
	}

	/** 이 테스트가 쓰는 협력자는 기관·정책 저장소 둘뿐이다. 사용량·비용 쪽 의존은 호출되지 않는다. */
	private OperationsService newService() {
		return new OperationsServiceImpl(
				organizationRepository,
				policyRepository,
				mock(OrganizationStatsRepository.class),
				mock(StorageUsageSnapshotRepository.class),
				mock(OrganizationUsageSnapshotRepository.class),
				mock(AiUsageRepository.class),
				mock(AiModelRepository.class),
				mock(OperationsCostRepository.class),
				mock(OperationsActivityRepository.class),
				mock(CohortCostRepository.class),
				mock(CurrentUserResolver.class)
		);
	}

	private static PatchBuilder patch() {
		return new PatchBuilder();
	}

	/** 필드 하나만 담은 요청을 읽기 좋게 만든다. 11개 인자를 매번 늘어놓으면 무엇을 보냈는지 보이지 않는다. */
	private static final class PatchBuilder {

		private OrganizationStatus organizationStatus;
		private BigDecimal monthlyAiBudget;
		private PatchField<Long> monthlyTokenLimit;
		private PatchField<Long> storageLimitBytes;
		private Integer dataRetentionDays;
		private DisclosureScope defaultDisclosureScope;
		private AiTier codeSessionTierCode;
		private Boolean allowManagerInvite;
		private Boolean allowDataExport;
		private Boolean allowZipSubmission;
		private Boolean allowGithubIntegration;

		PatchBuilder organizationStatus(OrganizationStatus value) {
			this.organizationStatus = value;
			return this;
		}

		PatchBuilder monthlyAiBudget(BigDecimal value) {
			this.monthlyAiBudget = value;
			return this;
		}

		PatchBuilder monthlyTokenLimit(PatchField<Long> value) {
			this.monthlyTokenLimit = value;
			return this;
		}

		PatchBuilder dataRetentionDays(Integer value) {
			this.dataRetentionDays = value;
			return this;
		}

		PatchBuilder allowManagerInvite(Boolean value) {
			this.allowManagerInvite = value;
			return this;
		}

		PatchBuilder allowDataExport(Boolean value) {
			this.allowDataExport = value;
			return this;
		}

		PatchBuilder allowZipSubmission(Boolean value) {
			this.allowZipSubmission = value;
			return this;
		}

		UpdateOperationSettingRequest build() {
			return new UpdateOperationSettingRequest(
					organizationStatus, monthlyAiBudget, monthlyTokenLimit, storageLimitBytes,
					dataRetentionDays, defaultDisclosureScope, codeSessionTierCode,
					allowManagerInvite, allowDataExport, allowZipSubmission, allowGithubIntegration);
		}
	}
}
