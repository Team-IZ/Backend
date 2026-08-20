package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcSessionRepositoryTest {

	/**
	 * 2026-08-21 발견·수정 — {@code openHint}가 건너뛴 앞 슬롯을 백필하는지를 SQL 문자열로 확인한다.
	 * 실제 CHECK 제약 통과 여부는 로컬에 Docker/정본 DDL이 없어 Testcontainers로는 못 돌렸고, 대신
	 * 운영 DB에서 트랜잭션을 열어({@code BEGIN}) 이 메서드가 만드는 것과 같은 SQL을 그대로 실행한
	 * 뒤 {@code ROLLBACK}으로 되돌리는 방식으로 직접 검증했다(부작용 없음, PR 설명 참고). 여기서는
	 * 그 SQL이 앞으로도 같은 모양으로 나가는지만 고정한다.
	 */
	@Test
	void 힌트2를_열_때_건너뛴_힌트1_슬롯을_빈_답변으로_백필하는_SQL을_만든다() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

		repository.openHint(UUID.randomUUID(), AnswerSlot.SECOND_HINT, 3L);

		verify(jdbc).update(sql.capture(), any(Object[].class));
		assertThat(sql.getValue())
				.contains("second_hint_presented_at")
				.contains("first_hint_answer_text = COALESCE(first_hint_answer_text, '')")
				.contains("first_hint_score = COALESCE(first_hint_score, 0)")
				.contains("first_hint_passed = COALESCE(first_hint_passed, false)")
				.contains("first_hint_answered_at = COALESCE(first_hint_answered_at, now())")
				.doesNotContain("question_answer_text");
	}

	@Test
	void 힌트1을_열_때_건너뛴_질문_슬롯을_빈_답변으로_백필하는_SQL을_만든다() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

		repository.openHint(UUID.randomUUID(), AnswerSlot.FIRST_HINT, 1L);

		verify(jdbc).update(sql.capture(), any(Object[].class));
		assertThat(sql.getValue())
				.contains("first_hint_presented_at")
				.contains("question_answer_text = COALESCE(question_answer_text, '')")
				.contains("question_score = COALESCE(question_score, 0)")
				.contains("question_passed = COALESCE(question_passed, false)")
				.contains("question_answered_at = COALESCE(question_answered_at, now())")
				.doesNotContain("second_hint_answer_text");
	}

	@Test
	void SMALLINT를_Integer로_반환하는_드라이버에서도_점수를_읽는다() throws Exception {
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getObject("question_score")).thenReturn(Integer.valueOf(4));

		assertThat(JdbcSessionRepository.nullableShort(resultSet, "question_score"))
				.isEqualTo((short) 4);
	}

	@Test
	void 비어_있는_점수는_null로_읽는다() throws Exception {
		ResultSet resultSet = mock(ResultSet.class);

		assertThat(JdbcSessionRepository.nullableShort(resultSet, "question_score")).isNull();
	}
}
