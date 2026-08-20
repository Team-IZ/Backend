package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectCurriculumRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectRequirementRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectVerificationConceptRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectVerificationConceptSetRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * project_id는 {@code GenerationType.UUID}라 애플리케이션이 메모리에서 만든다 — Hibernate가 실제
 * {@code INSERT INTO project}를 커밋 시점까지 미룰 수 있다는 뜻이다. 바로 뒤에 회차를 만드는
 * {@code projectDependencyRepository.createAssessmentRound}는 Hibernate를 거치지 않는 raw
 * JdbcTemplate이라 그 지연된 INSERT를 볼 방법이 없어, project row가 아직 없는 채로
 * project_assessment_round가 그 id를 참조해 FK 위반(409 DATA_INTEGRITY_VIOLATION)이 났다
 * (2026-08-20, POST /cohorts/{id}/projects 실제 재현).
 *
 * <p>이 테스트는 회차 INSERT 전에 project 저장이 <b>flush까지</b> 됐는지만 못 박는다 — 순서가
 * 반대로 바뀌거나 {@code save()}로 되돌아가면 실패해야 한다.
 */
class ProjectServiceImplCreateProjectTest {

	@Test
	void flushesProjectBeforeCreatingItsAssessmentRound() {
		ProjectRepository projectRepository = mock(ProjectRepository.class);
		ProjectDependencyRepository projectDependencyRepository = mock(ProjectDependencyRepository.class);

		ProjectServiceImpl service = new ProjectServiceImpl(
				projectRepository,
				mock(ProjectRequirementRepository.class),
				mock(ProjectVerificationConceptRepository.class),
				mock(ProjectVerificationConceptSetRepository.class),
				mock(ProjectCurriculumRepository.class),
				mock(CurriculumTeachesMappingRepository.class),
				mock(CurriculumVersionRepository.class),
				mock(CurriculumAnalysisRepository.class),
				mock(CurriculumSectionRepository.class),
				projectDependencyRepository);

		UUID orgId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		LocalDate start = LocalDate.of(2026, 8, 1);
		LocalDate end = LocalDate.of(2026, 8, 20);

		when(projectRepository.existsByCohortIdAndOrgIdAndName(cohortId, orgId, "1차 미니프로젝트"))
				.thenReturn(false);
		when(projectRepository.findMaxSequenceNo(cohortId, orgId)).thenReturn(0);
		when(projectRepository.saveAndFlush(any(Project.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		service.createProject(orgId, cohortId, "1차 미니프로젝트", ProjectCategory.MINI_PROJECT,
				start, end, Instant.parse("2026-08-20T14:59:00Z"), actorUserId);

		// saveAndFlush다 — save()로 되돌아가면 project row가 아직 안 심긴 채로 아래 회차 INSERT가 나간다.
		// projectId는 여기서 null이다(GenerationType.UUID는 Hibernate가 실제 persist 시점에 채우는
		// 값이라, Hibernate 없이 mock만 쓰는 이 테스트에서는 채워지지 않는다) — any()로 받는다.
		InOrder order = inOrder(projectRepository, projectDependencyRepository);
		order.verify(projectRepository).saveAndFlush(any(Project.class));
		order.verify(projectDependencyRepository).createAssessmentRound(
				any(), eq(orgId), eq(cohortId), eq("1차 미니프로젝트"),
				any(Instant.class), eq(actorUserId));

		verifyNoMoreInteractions(projectDependencyRepository);
	}
}
