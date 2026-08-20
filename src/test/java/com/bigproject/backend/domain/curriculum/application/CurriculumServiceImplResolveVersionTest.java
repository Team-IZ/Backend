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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code resolveVersionId}가 {@code findCurriculum}·{@code findSections}·{@code findUsedProjects}
 * 셋이 공유하는 공통 판정을 정확히 지키는지 고정한다(2026-08-20, 44차 R1/R3 공통 기반).
 */
class CurriculumServiceImplResolveVersionTest {

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

	/** versionId를 생략하면(null) 최신 버전(findAllByMaterialIdAndOrgIdOrderByVersionNoDesc의 첫 원소)으로 해석한다. */
	@Test
	void resolvesToLatestVersionWhenVersionIdIsOmitted() {
		UUID latestVersionId = UUID.randomUUID();
		CurriculumVersion latest = mock(CurriculumVersion.class);
		when(latest.getVersionId()).thenReturn(latestVersionId);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(latest));

		UUID resolved = service.resolveVersionId(materialId, null, orgId);

		assertThat(resolved).isEqualTo(latestVersionId);
	}

	/** 그 교안의 실제 버전이면 그대로 통과한다. */
	@Test
	void passesThroughAValidVersionOfThisMaterial() {
		UUID versionId = UUID.randomUUID();
		CurriculumVersion version = mock(CurriculumVersion.class);
		when(version.getVersionId()).thenReturn(versionId);
		when(version.getMaterialId()).thenReturn(materialId);
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.of(version));

		UUID resolved = service.resolveVersionId(materialId, versionId, orgId);

		assertThat(resolved).isEqualTo(versionId);
	}

	/** 존재하는 버전이지만 다른 교안의 것이면 404 — 존재는 하되 소속이 다르다. */
	@Test
	void rejectsAVersionThatBelongsToAnotherMaterial() {
		UUID versionId = UUID.randomUUID();
		UUID otherMaterialId = UUID.randomUUID();
		CurriculumVersion version = mock(CurriculumVersion.class);
		when(version.getMaterialId()).thenReturn(otherMaterialId);
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.of(version));

		assertThatThrownBy(() -> service.resolveVersionId(materialId, versionId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND));
	}

	/** 존재하지 않거나 다른 기관의 버전이면(레포지토리가 빈 값을 준다) 같은 404다 — 존재 여부를 남의 기관에 흘리지 않는다. */
	@Test
	void rejectsAnUnknownOrOtherOrgVersionWithTheSameCode() {
		UUID versionId = UUID.randomUUID();
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.resolveVersionId(materialId, versionId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND));
	}

	/** 교안 자체에 버전이 하나도 없으면(정상 흐름에서는 안 생기지만) 404 MATERIAL_NOT_FOUND다. */
	@Test
	void reportsMaterialNotFoundWhenTheMaterialHasNoVersionsAtAll() {
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.resolveVersionId(materialId, null, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));
	}
}
