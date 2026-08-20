package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * project·회차 1건을 새 트랜잭션에서 만든다.
 *
 * <p>별도 클래스인 이유: {@code ProjectServiceImpl.createProject}가 sequence_no 경합
 * ({@code uq_project_cohort_id_sequence_no})으로 실패하면 재시도해야 하는데, 실패한 시도가 절반쯤
 * 진행시킨 변경을 그대로 들고 다음 시도로 넘어가면 안 된다 — 매 시도가 완전히 새 트랜잭션이어야
 * 실패한 flush의 영향이 다음 시도로 새지 않는다. {@code REQUIRES_NEW}를 같은 클래스 안
 * private/protected 메서드에 걸면 self-invocation이라 프록시를 안 타 조용히 무시되므로
 * ({@code InvitationPersistenceService}가 REQUIRES_NEW 전용 클래스로 분리된 것과 같은 이유), 클래스를
 * 나눈다.
 */
@Service
@RequiredArgsConstructor
public class ProjectCreationTransaction {

	private final ProjectRepository projectRepository;
	private final ProjectDependencyRepository projectDependencyRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Project createOnce(UUID orgId, UUID cohortId, String name, ProjectCategory category,
			LocalDate startDate, LocalDate endDate, Instant submissionDueAt, UUID actorUserId) {

		int nextSequenceNo = projectRepository.findMaxSequenceNo(cohortId, orgId) + 1;

		Project project = category == ProjectCategory.MINI_PROJECT
				? Project.createMiniProject(orgId, cohortId, name, nextSequenceNo, startDate, endDate, actorUserId)
				: Project.createBigProject(orgId, cohortId, name, nextSequenceNo, startDate, endDate, actorUserId);

		// saveAndFlush다. project_id는 GenerationType.UUID라 애플리케이션이 메모리에서 만들어
		// Hibernate가 실제 INSERT를 커밋 시점까지 미룰 수 있다. 바로 아래 회차 INSERT는 Hibernate를
		// 거치지 않는 raw JdbcTemplate이라 그 지연을 볼 방법이 없어, project row가 아직 없는 채로
		// project_assessment_round가 그 id를 참조해 FK 위반(409)이 났다 — flush로 먼저 물리적으로 심는다.
		// sequence_no 경합이 나는 지점도 여기다: 동시에 두 요청이 같은 max+1을 읽으면 이 flush가
		// uq_project_cohort_id_sequence_no로 막힌다 — 그건 호출부가 재시도로 푼다.
		Project saved = projectRepository.saveAndFlush(project);

		// 22차 R5·R6 — 회차를 함께 만든다. 여태 만들지 않아서 화면으로 만든 프로젝트는 회차가 없는
		// 채로 남았고, 현황 탭이 회차를 못 찾았으며 제출 마감을 저장할 자리도 없었다.
		// 같은 트랜잭션이라 회차 INSERT가 실패하면 프로젝트도 함께 롤백된다 — 회차 없는 프로젝트를
		// 다시 만들지 않기 위해서다.
		projectDependencyRepository.createAssessmentRound(
				saved.getProjectId(), orgId, cohortId, name, submissionDueAt, actorUserId);

		return saved;
	}
}
