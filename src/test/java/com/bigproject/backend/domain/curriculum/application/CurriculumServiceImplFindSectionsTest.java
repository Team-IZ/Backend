package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code findSections}가 "성공한 분석이 없다"를 하나로 뭉치지 않고, 최근 시도가 FAILED로
 * 끝난 경우와 아직 안 끝난 경우(미시작·PENDING·RUNNING)를 다른 코드로 가르는지 고정한다.
 */
class CurriculumServiceImplFindSectionsTest {

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
	private final UUID versionId = UUID.randomUUID();
	private final UUID materialId = UUID.randomUUID();

	/** 최근 시도가 FAILED로 끝났으면 CURRICULUM_ANALYSIS_FAILED(영구) — 재시도해도 안 풀린다는 신호. */
	@Test
	void reportsAnalysisFailedWhenTheLatestAttemptFailed() {
		when(analysisRepository.findFirstByVersionIdAndStatusOrderByCompletedAtDesc(
				versionId, CurriculumAnalysisStatus.SUCCEEDED)).thenReturn(Optional.empty());
		CurriculumAnalysis failed = CurriculumAnalysis.createInitial(
				versionId, UUID.randomUUID(), 1, UUID.randomUUID(), "fingerprint", UUID.randomUUID());
		failed.fail("PROVIDER_ERROR", "PARSE", "AI 서버 오류", true, "RETRY");
		when(analysisRepository.findFirstByVersionIdOrderByRequestedAtDesc(versionId))
				.thenReturn(Optional.of(failed));

		assertThatThrownBy(() -> service.findSections(versionId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_ANALYSIS_FAILED));
	}

	/** 아직 분석을 건 적 없으면(PENDING·RUNNING도 마찬가지) CURRICULUM_ANALYSIS_NOT_COMPLETED(일시적). */
	@Test
	void reportsAnalysisNotCompletedWhenNoAttemptHasFinishedYet() {
		when(analysisRepository.findFirstByVersionIdAndStatusOrderByCompletedAtDesc(
				versionId, CurriculumAnalysisStatus.SUCCEEDED)).thenReturn(Optional.empty());
		when(analysisRepository.findFirstByVersionIdOrderByRequestedAtDesc(versionId))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findSections(versionId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_ANALYSIS_NOT_COMPLETED));
	}

	/** 교안이 없으면(다른 기관 포함) 404 — 버전 이력 조회도 새 조회이므로 같은 경로를 따른다. */
	@Test
	void findVersionHistoryRejectsWhenMaterialIsMissing() {
		when(materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)).thenReturn(false);

		assertThatThrownBy(() -> service.findVersionHistory(materialId, orgId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));
	}
}
