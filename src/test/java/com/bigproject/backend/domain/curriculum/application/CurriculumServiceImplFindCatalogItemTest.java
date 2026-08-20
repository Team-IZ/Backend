package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository.CurriculumCatalogRow;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 44차 R1 — {@code findCatalogItem}의 2-인자(최신 버전 고정, 삭제 가드용)와 3-인자
 * (versionId 지정 가능, 상세 조회용) 두 경로가 서로 독립적으로 도는지 고정한다.
 *
 * <p>특히 2-인자 경로는 {@code resolveVersionId}를 <b>거치지 않는다</b> — 옛 버전 상세를 열었다고
 * 삭제 가능 여부 판정({@code deleteCurriculum})이 달라지면 안 되기 때문이다.
 */
class CurriculumServiceImplFindCatalogItemTest {

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

	private static CurriculumCatalogRow row(UUID materialId, UUID versionId) {
		return new CurriculumCatalogRow(
				materialId, versionId, "제목", "file.pdf", 1, 10,
				CurriculumAnalysisStatus.SUCCEEDED, 5, 20, 3, 1,
				Instant.now(), "업로더");
	}

	/** 2-인자는 versionId 판정 없이 곧장 레포지토리의 2-인자(최신 버전 고정) 조회로 간다. */
	@Test
	void twoArgOverloadGoesStraightToTheMaterialWideLookupWithoutResolvingAVersion() {
		when(catalogRepository.findOne(orgId, materialId)).thenReturn(Optional.of(row(materialId, UUID.randomUUID())));

		CurriculumCatalogRow result = service.findCatalogItem(materialId, orgId);

		assertThat(result.materialId()).isEqualTo(materialId);
		verifyNoInteractions(versionRepository);
	}

	@Test
	void twoArgOverloadReportsMaterialNotFoundWhenEmpty() {
		when(catalogRepository.findOne(orgId, materialId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findCatalogItem(materialId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));
	}

	/** 3-인자에 versionId를 주면 resolveVersionId로 확인한 뒤 그 정확한 버전으로 3-인자 조회를 부른다. */
	@Test
	void threeArgOverloadResolvesTheGivenVersionThenQueriesForExactlyThatVersion() {
		UUID versionId = UUID.randomUUID();
		CurriculumVersion version = mock(CurriculumVersion.class);
		when(version.getVersionId()).thenReturn(versionId);
		when(version.getMaterialId()).thenReturn(materialId);
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.of(version));
		when(catalogRepository.findOne(orgId, materialId, versionId))
				.thenReturn(Optional.of(row(materialId, versionId)));

		CurriculumCatalogRow result = service.findCatalogItem(materialId, versionId, orgId);

		assertThat(result.versionId()).isEqualTo(versionId);
		verify(catalogRepository).findOne(orgId, materialId, versionId);
		verify(catalogRepository, never()).findOne(orgId, materialId);
	}

	/** versionId를 생략해도(null) 3-인자 경로는 resolveVersionId로 얻은 최신 버전을 3-인자 조회에 명시적으로 넘긴다. */
	@Test
	void threeArgOverloadResolvesLatestWhenVersionIdIsOmitted() {
		UUID latestVersionId = UUID.randomUUID();
		CurriculumVersion latest = mock(CurriculumVersion.class);
		when(latest.getVersionId()).thenReturn(latestVersionId);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(latest));
		when(catalogRepository.findOne(orgId, materialId, latestVersionId))
				.thenReturn(Optional.of(row(materialId, latestVersionId)));

		CurriculumCatalogRow result = service.findCatalogItem(materialId, null, orgId);

		assertThat(result.versionId()).isEqualTo(latestVersionId);
		verify(catalogRepository).findOne(orgId, materialId, latestVersionId);
	}

	/** versionId가 이 교안의 것이 아니면 404고, 레포지토리의 findOne(3-인자)는 아예 불리지 않는다. */
	@Test
	void threeArgOverloadRejectsBeforeQueryingWhenVersionDoesNotBelong() {
		UUID versionId = UUID.randomUUID();
		when(versionRepository.findByVersionIdAndOrgId(versionId, orgId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findCatalogItem(materialId, versionId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND));

		verify(catalogRepository, never()).findOne(orgId, materialId, versionId);
	}

	/**
	 * 삭제 가드 회귀 고정 — {@code deleteCurriculum}은 여전히 2-인자 {@code findCatalogItem}만 거친다.
	 * 44차 R1이 상세 조회에 versionId를 추가해도 삭제 판정의 모집단(교안 전체)은 바뀌면 안 된다.
	 */
	@Test
	void deleteCurriculumStillGoesThroughTheMaterialWideTwoArgPath() {
		UUID actorUserId = UUID.randomUUID();
		CurriculumMaterial material = mock(CurriculumMaterial.class);
		when(material.isDeleted()).thenReturn(false);
		when(materialRepository.findByMaterialIdAndOrgId(materialId, orgId)).thenReturn(Optional.of(material));
		when(catalogRepository.findOne(orgId, materialId))
				.thenReturn(Optional.of(row(materialId, UUID.randomUUID())));

		assertThatThrownBy(() -> service.deleteCurriculum(materialId, orgId, actorUserId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_MATERIAL_IN_USE));

		verify(catalogRepository).findOne(orgId, materialId);
		verify(catalogRepository, never()).findOne(orgId, materialId, null);
		verifyNoInteractions(versionRepository);
	}
}
