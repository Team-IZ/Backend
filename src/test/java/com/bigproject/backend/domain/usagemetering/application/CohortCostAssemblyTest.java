package com.bigproject.backend.domain.usagemetering.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.domain.platformgovernance.infrastructure.AiModelRepository;
import com.bigproject.backend.domain.usagemetering.domain.CohortCostRepository;
import com.bigproject.backend.domain.usagemetering.domain.OperationsActivityRepository;
import com.bigproject.backend.domain.usagemetering.domain.OperationsCostRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.AiUsageRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.OrganizationUsageSnapshotRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.StorageUsageSnapshotRepository;
import com.bigproject.backend.domain.usagemetering.presentation.dto.CohortCostResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OP-06 ⑤ 비용 탭 조립 로직. SQL이 아니라 <b>서비스가 월을 어떻게 펼치고 정렬하는지</b>를 본다.
 */
class CohortCostAssemblyTest {

	private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
	private final OrganizationPolicyRepository organizationPolicyRepository = mock(OrganizationPolicyRepository.class);
	private final CohortCostRepository cohortCostRepository = mock(CohortCostRepository.class);
	private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);

	private final OperationsService service = new OperationsServiceImpl(
			organizationRepository,
			organizationPolicyRepository,
			mock(OrganizationStatsRepository.class),
			mock(StorageUsageSnapshotRepository.class),
			mock(OrganizationUsageSnapshotRepository.class),
			mock(AiUsageRepository.class),
			mock(AiModelRepository.class),
			mock(OperationsCostRepository.class),
			mock(OperationsActivityRepository.class),
			cohortCostRepository,
			currentUserResolver
	);

	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID COHORT_ID = UUID.randomUUID();
	private static final UUID CLASS_A = UUID.randomUUID();
	private static final UUID CLASS_B = UUID.randomUUID();

	private static final YearMonth NOW = YearMonth.now(ZoneOffset.UTC);
	private static final YearMonth M2 = NOW.minusMonths(2);
	private static final YearMonth M1 = NOW.minusMonths(1);

	private static BigDecimal won(String v) {
		return new BigDecimal(v);
	}

	/** 기수는 2개월 전에 시작해 3개월 뒤 끝난다 — 미래 달이 잘리는지 보기 위한 배치다. */
	private void givenCohort() {
		when(organizationRepository.existsById(ORG_ID)).thenReturn(true);
		when(currentUserResolver.resolveCurrentUser()).thenReturn(new AuthUser(
				UUID.randomUUID(), ORG_ID, "sa@iz-get.com", "관리자", null, "ACTIVE",
				true, null, Role.SUPER_ADMIN, "ACTIVE"));

		when(cohortCostRepository.findCohortPeriod(ORG_ID, COHORT_ID))
				.thenReturn(new CohortCostRepository.CohortPeriod(
						COHORT_ID, "7기",
						M2.atDay(1),
						NOW.plusMonths(3).atEndOfMonth()));

		when(cohortCostRepository.findCohortMonthlyCost(eq(ORG_ID), eq(COHORT_ID), any(), any()))
				.thenReturn(List.of(
						new CohortCostRepository.MonthlyCohortCost(M2, won("100"), 10L),
						new CohortCostRepository.MonthlyCohortCost(M1, won("200"), 20L),
						new CohortCostRepository.MonthlyCohortCost(NOW, won("300"), 30L)));

		when(cohortCostRepository.findCohortMonthlyRoundNames(eq(ORG_ID), eq(COHORT_ID), any(), any()))
				.thenReturn(List.of(new CohortCostRepository.MonthlyRoundName(M1, "미프 2차")));

		when(cohortCostRepository.findActiveCohortCards(eq(ORG_ID), any(), any()))
				.thenReturn(List.of(new CohortCostRepository.CohortCard(
						COHORT_ID, "7기", won("300"), 250, M2.atDay(1), NOW.plusMonths(3).atEndOfMonth())));

		// A반은 세 달 모두, B반은 이번 달만 비용이 있다 — 0 채움을 보기 위한 배치다.
		when(cohortCostRepository.findClassSummaries(eq(ORG_ID), eq(COHORT_ID), any(), any()))
				.thenReturn(List.of(
						new CohortCostRepository.ClassSummary(CLASS_A, "A반", "박지현", won("60"), 6L),
						new CohortCostRepository.ClassSummary(CLASS_B, "B반", null, won("90"), 9L)));

		when(cohortCostRepository.findClassMonthlyCost(eq(ORG_ID), eq(COHORT_ID), any(), any()))
				.thenReturn(List.of(
						new CohortCostRepository.MonthlyClassAmount(CLASS_A, M2, won("10")),
						new CohortCostRepository.MonthlyClassAmount(CLASS_A, M1, won("20")),
						new CohortCostRepository.MonthlyClassAmount(CLASS_A, NOW, won("30")),
						new CohortCostRepository.MonthlyClassAmount(CLASS_B, NOW, won("90"))));

		when(organizationPolicyRepository.findByOrgIdAndStatus(ORG_ID, OrganizationPolicy.Status.ACTIVE))
				.thenReturn(Optional.empty());
	}

	private void givenOrgMonthly(List<CohortCostRepository.MonthlyAmount> rows) {
		when(cohortCostRepository.findOrganizationMonthlyCost(eq(ORG_ID), any(Instant.class), any(Instant.class)))
				.thenReturn(rows);
	}

	private CohortCostResponse call(CohortCostResponse.ClassCostSort sort) {
		return service.findCohortCost(ORG_ID, COHORT_ID, sort);
	}

	// ── 월 범위 ──────────────────────────────────────────────────

	@Test
	void 아직_오지_않은_달은_담지_않는다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		// 기수가 3개월 뒤까지인데도 이번 달까지만 3칸이다.
		assertThat(res.summary().monthly()).hasSize(3);
		assertThat(res.classes().get(0).monthly()).hasSize(3);
		assertThat(res.summary().month()).isEqualTo(NOW.toString());
	}

	// ── 정렬 방향 ────────────────────────────────────────────────

	@Test
	void 요약_월별은_최근이_앞이고_반_매트릭스는_오래된_것이_앞이다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		assertThat(res.summary().monthly()).extracting(CohortCostResponse.MonthlyCost::month)
				.containsExactly(NOW.toString(), M1.toString(), M2.toString());
		assertThat(res.classes().get(0).monthly())
				.extracting(CohortCostResponse.MonthlyClassCost::month)
				.containsExactly(M2.toString(), M1.toString(), NOW.toString());
	}

	// ── 0 채움 ──────────────────────────────────────────────────

	@Test
	void 비용이_없는_반_월_조합은_0으로_채운다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		CohortCostResponse.ClassCost b = res.classes().stream()
				.filter(c -> c.className().equals("B반")).findFirst().orElseThrow();
		// 행이 아예 없던 두 달도 칸은 있어야 매트릭스가 어긋나지 않는다.
		assertThat(b.monthly()).extracting(CohortCostResponse.MonthlyClassCost::amount)
				.containsExactly(BigDecimal.ZERO, BigDecimal.ZERO, won("90"));
	}

	@Test
	void 회차가_없는_달은_projectNames가_빈_배열이다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		// 최근이 앞이라 [0]=이번달, [1]=지난달
		assertThat(res.summary().monthly().get(0).projectNames()).isEmpty();
		assertThat(res.summary().monthly().get(1).projectNames()).containsExactly("미프 2차");
	}

	// ── 정렬 ────────────────────────────────────────────────────

	@Test
	void COHORT_AMOUNT는_기수_누적_많은_순이다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.COHORT_AMOUNT);

		// A반 $60 < B반 $90 이므로 이름순(A,B)이 뒤집힌다.
		assertThat(res.classes()).extracting(CohortCostResponse.ClassCost::className)
				.containsExactly("B반", "A반");
	}

	// ── 전월 대비 ────────────────────────────────────────────────

	@Test
	void 지난달_기록이_없으면_previousTotal은_null이고_changePct는_0이다() {
		givenCohort();
		givenOrgMonthly(List.of(new CohortCostRepository.MonthlyAmount(NOW, won("412"))));

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		// 0과 구분해야 화면이 `—`를 그린다.
		assertThat(res.summary().previousTotal()).isNull();
		assertThat(res.summary().changePct()).isEqualByComparingTo("0");
		assertThat(res.summary().total()).isEqualByComparingTo("412");
	}

	@Test
	void 전월_대비_증감은_비율이_아니라_퍼센트다() {
		givenCohort();
		givenOrgMonthly(List.of(
				new CohortCostRepository.MonthlyAmount(M1, won("400")),
				new CohortCostRepository.MonthlyAmount(NOW, won("448"))));

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		// 0.12가 아니라 12.0 — 화면 계약이 퍼센트다.
		assertThat(res.summary().changePct()).isEqualByComparingTo("12.0");
	}

	// ── 예산 ────────────────────────────────────────────────────

	@Test
	void 활성_정책이_없으면_예산은_null이다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		// 분모 없는 퍼센트는 만들 수 없으므로 화면이 비율을 안 그리게 null을 준다.
		assertThat(res.summary().budget()).isNull();
	}

	@Test
	void 기수_누적은_월별_합계와_같다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		assertThat(res.summary().cohortTotal()).isEqualByComparingTo("600"); // 100+200+300
	}

	@Test
	void 기수_카드는_기간_표기를_함께_준다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		CohortCostResponse.CohortCard card = res.summary().cohorts().get(0);
		assertThat(card.id()).isEqualTo(COHORT_ID);
		assertThat(card.trainees()).isEqualTo(250);
		// 전환기에 두 기수가 뜰 때 왜 같이 있는지를 카드가 스스로 말해야 한다.
		assertThat(card.period()).startsWith(M2.toString() + " ~ ");
	}

	@Test
	void 담당_매니저가_없는_반은_null로_내려간다() {
		givenCohort();
		givenOrgMonthly(List.of());

		CohortCostResponse res = call(CohortCostResponse.ClassCostSort.NAME);

		CohortCostResponse.ClassCost b = res.classes().stream()
				.filter(c -> c.className().equals("B반")).findFirst().orElseThrow();
		assertThat(b.managerName()).isNull();   // 화면은 `담당 없음`으로 그린다
		assertThat(b.cohortId()).isEqualTo(COHORT_ID);
	}

}
