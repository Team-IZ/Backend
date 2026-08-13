package com.bigproject.backend.domain.assessment.infrastructure;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcSessionRepositoryTest {

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
