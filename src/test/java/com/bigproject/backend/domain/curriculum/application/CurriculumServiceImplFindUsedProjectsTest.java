package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.TeachesRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 44차 R3 — {@code findUsedProjects}가 {@code versionId} 유무에 따라 정확히 다른 모집단을
 * {@code projectService.findProjectsUsingCurricula}에 넘기는지 고정한다.
 */
class CurriculumServiceImplFindUsedProjectsTest {

	private final CurriculumVersionRepository versionRepository = mock(CurriculumVersionRepository.class);
	private final CurriculumTeachesMappingRepository mappingRepository = mock(CurriculumTeachesMappingRepository.class);
	private final CurriculumAnalysisRepository analysisRepository = mock(CurriculumAnalysisRepository.class);
	private final CurriculumSectionRepository sectionRepository = mock(CurriculumSectionRepository.class);
	private final ProjectService projectService = mock(ProjectService.class);
	private final CurriculumMaterialRepository materialRepository = mock(CurriculumMaterialRepository.class);
	private final FileStorageService fileStorageService = mock(FileStorageService.class);
	private final AiCurriculumClient aiCurriculumClient = mock(AiCurriculumClient.class);
	private final CurriculumCatalogRepository catalogRepository = mock(CurriculumCatalogRepository.class);
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final TeachesRepository teachesRepository = mock(TeachesRepository.class);
	private final S3Client s3Client = mock(S3Client.class);

	private final CurriculumServiceImpl service = new CurriculumServiceImpl(
			versionRepository, mappingRepository, analysisRepository, sectionRepository,
			projectService, materialRepository, fileStorageService, aiCurriculumClient,
			catalogRepository, jdbcTemplate, teachesRepository, s3Client);

	private final UUID orgId = UUID.randomUUID();
	private final UUID materialId = UUID.randomUUID();

	private static CurriculumVersion versionWithId(UUID versionId) {
		CurriculumVersion version = mock(CurriculumVersion.class);
		when(version.getVersionId()).thenReturn(versionId);
		return version;
	}

	/** versionId 생략 — 이 교안의 모든 버전 ID를 그대로(순서·개수 포함) 넘긴다. usedProjectCount와 같은 모집단이다. */
	@Test
	void withoutVersionIdPassesEveryVersionOfTheMaterial() {
		UUID v1 = UUID.randomUUID();
		UUID v2 = UUID.randomUUID();
		CurriculumVersion version2 = versionWithId(v2);
		CurriculumVersion version1 = versionWithId(v1);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(version2, version1));

		service.findUsedProjects(materialId, orgId, null);

		verify(projectService).findProjectsUsingCurricula(eq(List.of(v2, v1)), eq(orgId));
	}

	/** versionId 지정 — 그 버전 하나만 담긴 리스트로 좁혀 넘긴다. */
	@Test
	void withVersionIdPassesExactlyThatOneVersion() {
		UUID versionId = UUID.randomUUID();
		CurriculumVersion version = versionWithId(versionId);
		when(version.getMaterialId()).thenReturn(materialId);
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.of(version));

		service.findUsedProjects(materialId, orgId, versionId);

		verify(projectService).findProjectsUsingCurricula(eq(List.of(versionId)), eq(orgId));
	}

	/** 잘못된 versionId는 projectService를 부르기도 전에 404로 끊긴다. */
	@Test
	void rejectsBeforeCallingProjectServiceWhenVersionIsInvalid() {
		UUID versionId = UUID.randomUUID();
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findUsedProjects(materialId, orgId, versionId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND));

		verifyNoInteractions(projectService);
	}

	/** 교안 자체에 버전이 없으면(versionId 생략 경로) 404 MATERIAL_NOT_FOUND — 종전과 동일한 동작이다. */
	@Test
	void reportsMaterialNotFoundWhenTheMaterialHasNoVersionsAndVersionIdIsOmitted() {
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.findUsedProjects(materialId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

		verifyNoInteractions(projectService);
	}

	/** 2-인자 오버로드는 3-인자에 versionId=null을 넘기는 것과 완전히 같다. */
	@Test
	void twoArgOverloadBehavesIdenticallyToOmittingVersionId() {
		UUID v1 = UUID.randomUUID();
		CurriculumVersion version1 = versionWithId(v1);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(version1));

		service.findUsedProjects(materialId, orgId);

		verify(projectService).findProjectsUsingCurricula(eq(List.of(v1)), eq(orgId));
	}
}
