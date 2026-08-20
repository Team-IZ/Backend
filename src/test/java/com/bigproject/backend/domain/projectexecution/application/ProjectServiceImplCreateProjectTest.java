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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code sequence_no} 경합({@code uq_project_cohort_id_sequence_no})으로 실제 삽입
 * ({@link ProjectCreationTransaction}, REQUIRES_NEW)이 실패했을 때 {@code createProject}가
 * 재시도하는지를 본다. 삽입 자체의 내용(flush 순서 등)은
 * {@link ProjectCreationTransactionTest}가 따로 본다.
 */
class ProjectServiceImplCreateProjectTest {

	private final ProjectRepository projectRepository = mock(ProjectRepository.class);
	private final ProjectDependencyRepository projectDependencyRepository = mock(ProjectDependencyRepository.class);
	private final ProjectCreationTransaction projectCreationTransaction = mock(ProjectCreationTransaction.class);

	private final ProjectServiceImpl service = new ProjectServiceImpl(
			projectRepository,
			mock(ProjectRequirementRepository.class),
			mock(ProjectVerificationConceptRepository.class),
			mock(ProjectVerificationConceptSetRepository.class),
			mock(ProjectCurriculumRepository.class),
			mock(CurriculumTeachesMappingRepository.class),
			mock(CurriculumVersionRepository.class),
			mock(CurriculumAnalysisRepository.class),
			mock(CurriculumSectionRepository.class),
			projectDependencyRepository,
			projectCreationTransaction);

	private final UUID orgId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID actorUserId = UUID.randomUUID();
	private final LocalDate start = LocalDate.of(2026, 8, 1);
	private final LocalDate end = LocalDate.of(2026, 8, 20);

	@Test
	void succeedsOnFirstAttemptWithoutRetrying() {
		when(projectRepository.existsByCohortIdAndOrgIdAndName(cohortId, orgId, "1차 미니프로젝트"))
				.thenReturn(false);
		Project created = mock(Project.class);
		when(projectCreationTransaction.createOnce(
				any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(created);

		Project result = service.createProject(orgId, cohortId, "1차 미니프로젝트", ProjectCategory.MINI_PROJECT,
				start, end, Instant.parse("2026-08-20T14:59:00Z"), actorUserId);

		assertThat(result).isSameAs(created);
		verify(projectCreationTransaction, times(1)).createOnce(
				any(), any(), any(), any(), any(), any(), any(), any());
	}

	/**
	 * 🔴 <b>재시도가 실제로 일어나는지가 이 테스트의 핵심이다.</b> 첫 시도가
	 * {@code uq_project_cohort_id_sequence_no} 경합으로 실패해도(둘 다 유효한 요청이라) 거절하지
	 * 않고, sequence_no를 다시 읽어 두 번째 시도로 성공시킨다.
	 */
	@Test
	void retriesOnceOnSequenceNoCollisionAndSucceeds() {
		when(projectRepository.existsByCohortIdAndOrgIdAndName(cohortId, orgId, "1차 미니프로젝트"))
				.thenReturn(false);
		Project created = mock(Project.class);
		when(projectCreationTransaction.createOnce(
				any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new DataIntegrityViolationException("uq_project_cohort_id_sequence_no"))
				.thenReturn(created);

		Project result = service.createProject(orgId, cohortId, "1차 미니프로젝트", ProjectCategory.MINI_PROJECT,
				start, end, Instant.parse("2026-08-20T14:59:00Z"), actorUserId);

		assertThat(result).isSameAs(created);
		verify(projectCreationTransaction, times(2)).createOnce(
				any(), any(), any(), any(), any(), any(), any(), any());
	}

	/** 경합이 재시도 상한을 넘겨 계속되면 포기하고 그대로 던진다 — 무한 재시도로 숨기지 않는다. */
	@Test
	void givesUpAfterExhaustingRetries() {
		when(projectRepository.existsByCohortIdAndOrgIdAndName(cohortId, orgId, "1차 미니프로젝트"))
				.thenReturn(false);
		when(projectCreationTransaction.createOnce(
				any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new DataIntegrityViolationException("uq_project_cohort_id_sequence_no"));

		assertThatThrownBy(() -> service.createProject(orgId, cohortId, "1차 미니프로젝트",
				ProjectCategory.MINI_PROJECT, start, end,
				Instant.parse("2026-08-20T14:59:00Z"), actorUserId))
				.isInstanceOf(DataIntegrityViolationException.class);

		verify(projectCreationTransaction, times(3)).createOnce(
				any(), any(), any(), any(), any(), any(), any(), any());
	}
}
