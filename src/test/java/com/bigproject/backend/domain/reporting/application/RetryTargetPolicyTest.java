package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.ConceptReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재시험 대상은 <b>도달 단계 하나로 정해진다</b>는 것을 못 박는다 — 2단 미만만 대상이다.
 *
 * <p>이 테스트가 있는 이유는 저장된 판정({@code report_evidence.decision_code})이 실제로 정책과
 * 어긋난 적이 있기 때문이다. 2단을 통과한 개념이 {@code REVIEW_REQUIRED}로 얼어 있었고, 그대로
 * 내보내면 <b>학생이 합격한 개념을 다시 본다.</b> 다시 보기는 회차당 한 번뿐이라 그 한 번을
 * 잃는 것은 되돌릴 수 없다(23차 R2).
 */
class RetryTargetPolicyTest {

	private static final UUID USER = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();

	private TraineeReportQueryRepository queryRepository;
	private TraineeReportServiceImpl service;

	@BeforeEach
	void setUp() {
		queryRepository = mock(TraineeReportQueryRepository.class);
		service = new TraineeReportServiceImpl(queryRepository, new ObjectMapper());
	}

	/**
	 * 23차에 보고된 실제 리포트다. 세 개념이 3·2·2단인데 저장된 판정은 뒤 둘을 대상으로 두고 있었다.
	 */
	@Test
	@DisplayName("2단을 통과한 개념은 저장된 판정이 REVIEW_REQUIRED여도 대상이 아니다")
	void doesNotRetestConceptsThatReachedTheSecondStage() {
		givenConcepts(
				concept("예외 처리와 롤백 전략", 3, false),
				concept("API 응답 계약 설계", 2, true),
				concept("영속성 매핑과 지연 로딩", 2, true));

		assertThat(concepts())
				.extracting(ConceptReportResponse::level, ConceptReportResponse::isRetryTarget)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(3, false),
						org.assertj.core.groups.Tuple.tuple(2, false),
						org.assertj.core.groups.Tuple.tuple(2, false));
	}

	@Test
	@DisplayName("0·1단은 저장된 판정이 비어 있어도 대상이다")
	void retestsEveryConceptBelowTheSecondStage() {
		givenConcepts(concept("트랜잭션 경계", 0, false), concept("인덱스 설계", 1, false));

		assertThat(concepts()).extracting(ConceptReportResponse::isRetryTarget)
				.containsExactly(true, true);
	}

	/** 화면이 두 필드를 나란히 그리므로 어긋나면 카드 하나가 서로 다른 말을 한다. */
	@Test
	@DisplayName("level과 isRetryTarget은 언제나 같은 기준선을 가리킨다")
	void levelAndRetryTargetNeverContradictEachOther() {
		givenConcepts(concept("a", 0, false), concept("b", 1, true), concept("c", 2, true),
				concept("d", 3, true), concept("e", 4, true));

		assertThat(concepts()).allSatisfy(concept -> assertThat(concept.isRetryTarget())
				.isEqualTo(concept.level() < TraineeReportServiceImpl.RETRY_TARGET_BELOW_LEVEL));
	}

	// ------------------------------------------------------------------ fixture

	private List<ConceptReportResponse> concepts() {
		return service.findMyReports(USER).reportsById().get(ROUND.toString()).concepts();
	}

	/** @param storedDecision 저장된 {@code decision_code}가 REVIEW_REQUIRED인가 */
	private ConceptRow concept(String name, int level, boolean storedDecision) {
		return new ConceptRow(REPORT, UUID.randomUUID(), name, 1, level,
				"결과 설명", "발췌", null, storedDecision, null, true);
	}

	private void givenConcepts(ConceptRow... rows) {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(new RoundRow(
				ROUND, "미프 1차 이해도 확인", 1, "미니프로젝트",
				REPORT, UUID.randomUUID(), "FULL", 3, 0,
				UUID.randomUUID(), "COMPLETED", null, "APPROVED",
				"RELEASED", "FULL",
				null, null, Instant.parse("2026-07-01T00:00:00Z"), true,
				null, null, null)));
		when(queryRepository.findConcepts(USER)).thenReturn(List.of(rows));
	}
}
