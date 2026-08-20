package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.TeachesRepository;
import com.bigproject.backend.domain.curriculum.presentation.dto.CohortCurriculumResponse;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 44차 R2 회신 — 프론트가 지목한 것은 동작이 아니라 문서 문구였다는 것을 잠그는 회귀 테스트다.
 * 같은 교안이라도 회차마다 다른 버전을 연결했으면(1차=v1, 3차=v2) 행이 버전 단위로 나뉘고,
 * 각 행의 {@code linkedProjects[]}에는 그 버전을 쓴 회차만 담긴다.
 */
class CurriculumServiceImplLinkedCurriculaTest {

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

	@Test
	void splitsIntoOneRowPerVersionWhenRoundsInTheSameCohortUseDifferentVersions() {
		UUID cohortId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		UUID v1 = UUID.randomUUID();
		UUID v2 = UUID.randomUUID();
		UUID round1 = UUID.randomUUID();
		UUID round3 = UUID.randomUUID();

		when(catalogRepository.cohortExists(cohortId, orgId)).thenReturn(true);

		ProjectService.CohortCurriculumLink linkRound1 =
				new ProjectService.CohortCurriculumLink(v1, round1, "미니프로젝트 1차", 1);
		ProjectService.CohortCurriculumLink linkRound3 =
				new ProjectService.CohortCurriculumLink(v2, round3, "미니프로젝트 3차", 3);
		when(projectService.findCurriculumLinksInCohort(cohortId, orgId))
				.thenReturn(List.of(linkRound1, linkRound3));

		CurriculumVersion version1 = mock(CurriculumVersion.class);
		when(version1.getVersionId()).thenReturn(v1);
		when(version1.getMaterialId()).thenReturn(materialId);
		when(version1.getVersionNo()).thenReturn(1);
		CurriculumVersion version2 = mock(CurriculumVersion.class);
		when(version2.getVersionId()).thenReturn(v2);
		when(version2.getMaterialId()).thenReturn(materialId);
		when(version2.getVersionNo()).thenReturn(2);
		when(versionRepository.findAllById(List.of(v1, v2))).thenReturn(List.of(version1, version2));

		List<CurriculumService.LinkedCurriculum> result = service.findLinkedCurriculaForCohort(cohortId, orgId);

		assertThat(result).hasSize(2);

		CurriculumService.LinkedCurriculum row1 = result.stream()
				.filter(r -> r.version().getVersionId().equals(v1)).findFirst().orElseThrow();
		CurriculumService.LinkedCurriculum row2 = result.stream()
				.filter(r -> r.version().getVersionId().equals(v2)).findFirst().orElseThrow();

		assertThat(row1.linkedProjects()).containsExactly(linkRound1);
		assertThat(row2.linkedProjects()).containsExactly(linkRound3);

		// 44차 R2 D2 — linkedProjects[]의 각 항목이 자기 버전을 직접 들고 있다.
		CohortCurriculumResponse response1 = CohortCurriculumResponse.from(row1);
		assertThat(response1.linkedProjects()).hasSize(1);
		assertThat(response1.linkedProjects().get(0).curriculumVersionId()).isEqualTo(v1);

		CohortCurriculumResponse response2 = CohortCurriculumResponse.from(row2);
		assertThat(response2.linkedProjects()).hasSize(1);
		assertThat(response2.linkedProjects().get(0).curriculumVersionId()).isEqualTo(v2);
	}
}
