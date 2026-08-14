package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcTraineeRosterInvitationSqlTest {

	@Test
	void cohortTotalCombinesAcceptedMembersAndPendingInvitations() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
				.thenReturn(2);
		JdbcTraineeRosterRepository repository = new JdbcTraineeRosterRepository(jdbcTemplate);

		repository.countCohortTotal(UUID.randomUUID(), UUID.randomUUID(), null);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), any(Object[].class));
		assertThat(sql.getValue())
				.contains("FROM cohort_member")
				.contains("UNION ALL")
				.contains("FROM user_invitation")
				.contains("u.status = 'PENDING'")
				.contains("ui.target_role_code = 'TRAINEE'")
				.contains("invitation_cohort.status <> 'CLOSED'")
				.contains("FROM roster r");
	}

	/**
	 * 명단은 <b>발송 실패를 따로 알려줘야 한다.</b>
	 *
	 * <p>{@code pending_invitation_token_id}는 PENDING·SENT·DELIVERY_FAILED·EXPIRED에 모두 채워지므로
	 * 그것만으로는 "메일은 갔고 아직 가입 안 함"과 "메일이 아예 안 나감"이 구분되지 않는다. 구분이 없으면
	 * 화면이 [초대 재발송] 대상을 고를 수 없다 — 900명 배치에서 366명이 실패해도 누가 그 366명인지 모른다.
	 *
	 * <p>UNION ALL은 컬럼을 <b>위치로</b> 맞추므로 두 분기가 같은 자리에 이 값을 실어야 한다.
	 * 한쪽만 넣으면 컬럼 수가 어긋나 질의가 통째로 실패하고, 순서가 어긋나면 조용히 엉뚱한 값이 실린다.
	 */
	@Test
	void rosterTellsAFailedInvitationApartFromOneThatIsMerelyUnaccepted() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
				.thenReturn(0L);
		when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
				.thenReturn(List.of());
		JdbcTraineeRosterRepository repository = new JdbcTraineeRosterRepository(jdbcTemplate);

		repository.findRoster(
				new TraineeRosterRepository.RosterCriteria(
						UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(),
						null, false, null, null, TraineeRosterSort.NAME),
				PageRequest.of(0, 20));

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
		assertThat(sql.getValue())
				// 초대 대기 분기는 원장 상태에서 읽고,
				.contains("ui.status = 'DELIVERY_FAILED'")
				// 수락해서 들어온 분기는 진행 중인 초대가 없으므로 늘 거짓이며,
				.contains("FALSE AS invitation_delivery_failed")
				// 바깥 SELECT가 그것을 응답까지 올린다.
				.contains("r.invitation_delivery_failed");
	}
}
