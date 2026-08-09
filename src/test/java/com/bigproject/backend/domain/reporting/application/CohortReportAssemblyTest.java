package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.ClassRiskRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.CohortReportHeader;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.CohortScale;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.ExcludedRow;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.ClassRiskRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OP-05 조립 로직 중 <b>서비스가 값을 만들어 내는 두 지점</b>만 본다.
 * SQL 결과를 그대로 옮기는 부분은 여기서 볼 것이 없다.
 *
 * <ol>
 *   <li>제외 인원(excluded) — 예전에는 하드코딩 0이라 화면의 뺄셈이 맞지 않았다.</li>
 *   <li>반별 위험자의 `기수 전체` 기준선 행 — 없으면 화면이 조용히 기준선을 안 그린다.</li>
 * </ol>
 */
class CohortReportAssemblyTest {

	private static final UUID COHORT_ID = UUID.randomUUID();
	private static final UUID DIAGNOSIS_SNAPSHOT_ID = UUID.randomUUID();
	private static final UUID OUTCOME_SNAPSHOT_ID = UUID.randomUUID();

	private final CohortReportQueryRepository queryRepository = mock(CohortReportQueryRepository.class);
	private final CohortReportService service = new CohortReportServiceImpl(queryRepository);

	@BeforeEach
	void setUp() {
		// 결산 스냅샷까지 있어야 classRisk가 채워진다(CONFIRMED).
		when(queryRepository.findHeader(COHORT_ID)).thenReturn(Optional.of(new CohortReportHeader(
				COHORT_ID, "1기",
				LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
				UUID.randomUUID(), DIAGNOSIS_SNAPSHOT_ID, Instant.parse("2026-06-30T00:00:00Z"),
				UUID.randomUUID(), OUTCOME_SNAPSHOT_ID, Instant.parse("2026-07-01T00:00:00Z"),
				8, 8
		)));
		when(queryRepository.findScale(COHORT_ID)).thenReturn(new CohortScale(250, 10));
		when(queryRepository.findCurriculumSummaries(any())).thenReturn(List.of());
		when(queryRepository.findRoundConcepts(any())).thenReturn(List.of());
		when(queryRepository.findConceptDistribution(any())).thenReturn(List.of());
		when(queryRepository.findGroupShortfalls(any())).thenReturn(List.of());
		when(queryRepository.findTopStudents(any())).thenReturn(List.of());
		when(queryRepository.findTopStudentRounds(any())).thenReturn(List.of());
		when(queryRepository.findExcluded(any())).thenReturn(ExcludedRow.zero());
		when(queryRepository.findClassRisks(any())).thenReturn(List.of());
	}

	@Test
	void 제외_인원은_스냅샷_페이로드에서_읽은_값이_그대로_실린다() {
		when(queryRepository.findExcluded(DIAGNOSIS_SNAPSHOT_ID)).thenReturn(new ExcludedRow(12, 3, 5));

		CohortDiagnosisResponse response = service.findClassDiagnosis(COHORT_ID);

		// 화면 요약 탭의 `전체 응시 − 미응시 − 무효 − 중단 = 채점` 줄이 이 셋을 그대로 쓴다.
		assertThat(response.excluded().notTaken()).isEqualTo(12);
		assertThat(response.excluded().invalid()).isEqualTo(3);
		assertThat(response.excluded().interrupted()).isEqualTo(5);
	}

	@Test
	void 뷰가_기수_전체_행을_주면_그대로_쓴다() {
		when(queryRepository.findClassRisks(OUTCOME_SNAPSHOT_ID)).thenReturn(List.of(
				new ClassRiskRow(null, "기수 전체", 250, 60),
				new ClassRiskRow(UUID.randomUUID(), "A반", 125, 20),
				new ClassRiskRow(UUID.randomUUID(), "B반", 125, 40)
		));

		CohortDiagnosisResponse response = service.findClassDiagnosis(COHORT_ID);

		// 뷰의 집계 기준이 권위다. 합계(20+40=60)와 우연히 같더라도 덮어쓰지 않는다.
		assertThat(response.classRisk()).hasSize(3);
		assertThat(overall(response)).isNotNull();
		assertThat(overall(response).traineeCount()).isEqualTo(250);
		assertThat(overall(response).atRiskCount()).isEqualTo(60);
	}

	@Test
	void 뷰에_기수_전체_행이_없으면_합계로_만들어_붙인다() {
		when(queryRepository.findClassRisks(OUTCOME_SNAPSHOT_ID)).thenReturn(List.of(
				new ClassRiskRow(UUID.randomUUID(), "A반", 120, 20),
				new ClassRiskRow(UUID.randomUUID(), "B반", 130, 40)
		));

		CohortDiagnosisResponse response = service.findClassDiagnosis(COHORT_ID);

		// 이 행이 없으면 화면(DiagnosisSummary·ClassOps)이 에러 없이 기준선을 안 그린다.
		assertThat(response.classRisk()).hasSize(3);
		assertThat(response.classRisk().get(0).className()).isEqualTo("기수 전체");
		assertThat(overall(response).traineeCount()).isEqualTo(250);
		assertThat(overall(response).atRiskCount()).isEqualTo(60);
	}

	@Test
	void 반이_하나도_없으면_기준선_행을_지어내지_않는다() {
		when(queryRepository.findClassRisks(OUTCOME_SNAPSHOT_ID)).thenReturn(List.of());

		CohortDiagnosisResponse response = service.findClassDiagnosis(COHORT_ID);

		// 0명짜리 `기수 전체`를 만들면 "위험자 0%"라는 없는 사실을 주장하게 된다.
		assertThat(response.classRisk()).isEmpty();
	}

	private static ClassRiskRate overall(CohortDiagnosisResponse response) {
		return response.classRisk().stream()
				.filter(row -> "기수 전체".equals(row.className()))
				.findFirst()
				.orElse(null);
	}
}
