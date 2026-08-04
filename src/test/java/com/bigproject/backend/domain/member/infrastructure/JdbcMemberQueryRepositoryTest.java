package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberQueryRepository;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.SortDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMemberQueryRepositoryTest {
	private JdbcTemplate jdbcTemplate;
	private JdbcMemberQueryRepository repository;

	private UUID organizationId;
	private UUID cohortId;
	private UUID classroomId;
	private UUID pendingManagerId;
	private UUID deletedManagerId;
	private UUID traineeId;
	private UUID cohortMemberId;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"sa",
				""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		repository = new JdbcMemberQueryRepository(jdbcTemplate);
		createSchema();
		insertFixture();
	}

	@Test
	void pagesManagersWithoutAssignmentJoinInflatingTotal() {
		var result = repository.findManagers(new MemberQueryRepository.ManagerCriteria(
				organizationId,
				null,
				null,
				null,
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC
		));

		assertThat(result.totalElements()).isEqualTo(2);
		assertThat(result.content()).extracting(MemberQueryRepository.ManagerRow::memberId)
				.containsExactly(pendingManagerId, deletedManagerId);
		assertThat(repository.findManagerAssignments(List.of(pendingManagerId)))
				.hasSize(1)
				.allSatisfy(assignment -> assertThat(assignment.managerId()).isEqualTo(pendingManagerId));
	}

	@Test
	void mapsDatabaseStateInputsNeededForInvitedAndDeletedApiStatus() {
		var invited = repository.findManagers(new MemberQueryRepository.ManagerCriteria(
				organizationId,
				null,
				AccountStatus.INVITED,
				"newmgr@example.com",
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC
		));
		var inactive = repository.findManagers(new MemberQueryRepository.ManagerCriteria(
				organizationId,
				null,
				AccountStatus.INACTIVE,
				null,
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC
		));

		assertThat(invited.content()).singleElement().satisfies(row -> {
			assertThat(row.databaseStatus()).isEqualTo("PENDING");
			assertThat(row.deleted()).isFalse();
		});
		assertThat(inactive.content()).singleElement().satisfies(row -> {
			assertThat(row.memberId()).isEqualTo(deletedManagerId);
			assertThat(row.deleted()).isTrue();
		});
	}

	@Test
	void readsTraineeWithCurrentClassroomAndClassFilter() {
		var result = repository.findTrainees(new MemberQueryRepository.TraineeCriteria(
				cohortId,
				organizationId,
				classroomId,
				null,
				"trainee",
				0,
				20
		));

		assertThat(result.totalElements()).isEqualTo(1);
		assertThat(result.content()).singleElement().satisfies(row -> {
			assertThat(row.memberId()).isEqualTo(traineeId);
			assertThat(row.membershipStatus()).isEqualTo("ACTIVE");
		});
		assertThat(repository.findCurrentClassrooms(List.of(cohortMemberId))).singleElement()
				.satisfies(row -> {
					assertThat(row.classroomId()).isEqualTo(classroomId);
					assertThat(row.classroomName()).isEqualTo("A반");
				});
	}

	private void createSchema() {
		jdbcTemplate.execute("CREATE TABLE organization (org_id UUID PRIMARY KEY, deleted_at TIMESTAMP WITH TIME ZONE)");
		jdbcTemplate.execute("CREATE TABLE \"role\" (role_id UUID PRIMARY KEY, code VARCHAR(100) NOT NULL)");
		jdbcTemplate.execute("""
				CREATE TABLE app_user (
					user_id UUID PRIMARY KEY,
					org_id UUID,
					role_id UUID NOT NULL,
					email VARCHAR(320) NOT NULL,
					name VARCHAR(200) NOT NULL,
					status VARCHAR(100) NOT NULL,
					last_login_at TIMESTAMP WITH TIME ZONE,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE cohort (
					cohort_id UUID PRIMARY KEY,
					org_id UUID NOT NULL,
					name VARCHAR(200) NOT NULL,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE \"class\" (
					class_id UUID PRIMARY KEY,
					org_id UUID NOT NULL,
					cohort_id UUID NOT NULL,
					name VARCHAR(200) NOT NULL,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE manager_assignment (
					assignment_id UUID PRIMARY KEY,
					manager_user_id UUID NOT NULL,
					org_id UUID NOT NULL,
					class_id UUID NOT NULL,
					assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
					unassigned_at TIMESTAMP WITH TIME ZONE,
					status VARCHAR(30) NOT NULL,
					assigned_by UUID NOT NULL,
					unassigned_by UUID,
					unassigned_reason VARCHAR(50),
					created_at TIMESTAMP WITH TIME ZONE NOT NULL
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE cohort_member (
					cohort_member_id UUID PRIMARY KEY,
					cohort_id UUID NOT NULL,
					user_id UUID NOT NULL,
					org_id UUID NOT NULL,
					status VARCHAR(100) NOT NULL,
					left_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE class_membership (
					class_membership_id UUID PRIMARY KEY,
					class_id UUID NOT NULL,
					cohort_member_id UUID NOT NULL,
					org_id UUID NOT NULL,
					assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
					unassigned_at TIMESTAMP WITH TIME ZONE
				)
				""");
	}

	private void insertFixture() {
		organizationId = UUID.randomUUID();
		cohortId = UUID.randomUUID();
		classroomId = UUID.randomUUID();
		pendingManagerId = UUID.randomUUID();
		deletedManagerId = UUID.randomUUID();
		traineeId = UUID.randomUUID();
		cohortMemberId = UUID.randomUUID();
		UUID managerRoleId = UUID.randomUUID();
		UUID traineeRoleId = UUID.randomUUID();
		Instant now = Instant.parse("2026-07-22T00:00:00Z");

		jdbcTemplate.update("INSERT INTO organization (org_id, deleted_at) VALUES (?, NULL)", organizationId);
		jdbcTemplate.update("INSERT INTO \"role\" (role_id, code) VALUES (?, 'MANAGER')", managerRoleId);
		jdbcTemplate.update("INSERT INTO \"role\" (role_id, code) VALUES (?, 'TRAINEE')", traineeRoleId);
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, ?, ?, ?, ?, 'PENDING', NULL, NULL)",
				pendingManagerId, organizationId, managerRoleId, "newmgr@example.com", "가입 대기"
		);
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
				deletedManagerId,
				organizationId,
				managerRoleId,
				"former@example.com",
				"퇴사자",
				Timestamp.from(now.minusSeconds(3600)),
				Timestamp.from(now)
		);
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, ?, ?, ?, ?, 'ACTIVE', NULL, NULL)",
				traineeId, organizationId, traineeRoleId, "trainee@example.com", "교육생"
		);
		jdbcTemplate.update("INSERT INTO cohort VALUES (?, ?, '7기', NULL)", cohortId, organizationId);
		jdbcTemplate.update("INSERT INTO \"class\" VALUES (?, ?, ?, 'A반', NULL)", classroomId, organizationId, cohortId);
		jdbcTemplate.update(
				"INSERT INTO manager_assignment VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', ?, NULL, NULL, ?)",
				UUID.randomUUID(), pendingManagerId, organizationId, classroomId,
				Timestamp.from(now.minusSeconds(3600)), pendingManagerId, Timestamp.from(now)
		);
		jdbcTemplate.update(
				"INSERT INTO cohort_member VALUES (?, ?, ?, ?, 'ACTIVE', NULL)",
				cohortMemberId, cohortId, traineeId, organizationId
		);
		jdbcTemplate.update(
				"INSERT INTO class_membership VALUES (?, ?, ?, ?, ?, NULL)",
				UUID.randomUUID(), classroomId, cohortMemberId, organizationId, Timestamp.from(now)
		);
	}
}
