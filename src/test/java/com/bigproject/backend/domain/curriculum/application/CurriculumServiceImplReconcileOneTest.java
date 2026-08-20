package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인스턴스가 둘 이상이면 {@code pollPendingCurriculumAnalyses}의
 * {@code findAllByStatusIn}이 같은 분석 행을 두 인스턴스에 동시에 넘길 수 있다.
 * {@code CurriculumAnalysis.start()}의 상태 검사는 <b>메모리 안의 detached 엔티티</b>만
 * 보므로 둘 다 통과해 섹션·매핑이 두 배로 저장됐다(2026-08-20).
 *
 * <p>이 테스트는 {@code CurriculumAnalysisRepository#claimForCompletion}(DB 레벨 선점)이
 * 실제로 두 번째 호출을 막는지 못 박는다 — {@code reconcileOne}은 이 리포지토리가 무엇을
 * 돌려주는지만 보고 판단하므로, "0건 반환"으로 경합에서 진 상황을 그대로 흉내낼 수 있다.
 */
class CurriculumServiceImplReconcileOneTest {

	private final CurriculumVersionRepository curriculumVersionRepository = mock(CurriculumVersionRepository.class);
	private final CurriculumAnalysisRepository analysisRepository = mock(CurriculumAnalysisRepository.class);
	private final CurriculumSectionRepository sectionRepository = mock(CurriculumSectionRepository.class);
	private final CurriculumMaterialRepository materialRepository = mock(CurriculumMaterialRepository.class);
	private final AiCurriculumClient aiCurriculumClient = mock(AiCurriculumClient.class);
	private final TeachesRepository teachesRepository = mock(TeachesRepository.class);

	private CurriculumServiceImpl service() {
		return new CurriculumServiceImpl(
				curriculumVersionRepository,
				mock(CurriculumTeachesMappingRepository.class),
				analysisRepository,
				sectionRepository,
				mock(ProjectService.class),
				materialRepository,
				mock(FileStorageService.class),
				aiCurriculumClient,
				mock(CurriculumCatalogRepository.class),
				mock(org.springframework.jdbc.core.JdbcTemplate.class),
				teachesRepository,
				mock(software.amazon.awssdk.services.s3.S3Client.class));
	}

	private CurriculumAnalysis analysis(UUID analysisId, UUID versionId) {
		CurriculumAnalysis analysis = mock(CurriculumAnalysis.class);
		when(analysis.getAnalysisId()).thenReturn(analysisId);
		when(analysis.getVersionId()).thenReturn(versionId);
		when(analysis.getExternalJobId()).thenReturn(UUID.randomUUID());
		when(analysis.getRequestedAt()).thenReturn(OffsetDateTime.now());
		return analysis;
	}

	private void stubSuccessfulAiResult(UUID jobId, UUID versionId, UUID materialId, UUID orgId,
			CurriculumAnalysis analysis) {
		AiCurriculumClient.TeachesResult teaches = new AiCurriculumClient.TeachesResult(
				"이름", "이름", "설명", java.math.BigDecimal.ONE, 1, 2, "kind", "evidence", List.of());
		AiCurriculumClient.SectionResult section = new AiCurriculumClient.SectionResult(
				1, "제목", 1, 2, List.of(), java.math.BigDecimal.ONE, List.of(teaches));
		AiCurriculumClient.CurriculumResultPayload payload = new AiCurriculumClient.CurriculumResultPayload(
				versionId.toString(), 1, "h1", "p1", "DONE", "OK", false, List.of(section));
		AiCurriculumClient.AnalysisResult result = new AiCurriculumClient.AnalysisResult(
				jobId.toString(), versionId.toString(), "SUCCEEDED", null, null, null, payload);
		when(aiCurriculumClient.checkStatus(jobId.toString())).thenReturn(result);

		CurriculumVersion version = mock(CurriculumVersion.class);
		when(version.getMaterialId()).thenReturn(materialId);
		when(curriculumVersionRepository.findById(versionId)).thenReturn(Optional.of(version));

		CurriculumMaterial material = mock(CurriculumMaterial.class);
		when(material.getOrgId()).thenReturn(orgId);
		when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
	}

	@Test
	void skipsPersistingWhenAnotherInstanceAlreadyClaimedTheAnalysis() {
		UUID analysisId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		CurriculumAnalysis analysis = analysis(analysisId, versionId);

		stubSuccessfulAiResult(analysis.getExternalJobId(), versionId, materialId, orgId, analysis);

		// 🔴 경합에서 졌다: 다른 인스턴스가 먼저 상태를 옮겨 놔서 0건이 걸린다.
		when(analysisRepository.claimForCompletion(eq(analysisId), any(), any())).thenReturn(0);

		service().reconcileOne(analysis);

		verify(sectionRepository, never()).save(any());
		verify(analysis, never()).succeed();
	}

	@Test
	void persistsResultWhenClaimSucceeds() {
		UUID analysisId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		CurriculumAnalysis analysis = analysis(analysisId, versionId);

		stubSuccessfulAiResult(analysis.getExternalJobId(), versionId, materialId, orgId, analysis);

		when(analysisRepository.claimForCompletion(eq(analysisId), any(), any())).thenReturn(1);
		when(sectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		com.bigproject.backend.domain.curriculum.domain.Teaches teaches =
				mock(com.bigproject.backend.domain.curriculum.domain.Teaches.class);
		when(teaches.getTeachesId()).thenReturn(UUID.randomUUID());
		when(teachesRepository.findByOrgIdAndNormalizedName(any(), any())).thenReturn(Optional.empty());
		when(teachesRepository.save(any())).thenReturn(teaches);

		service().reconcileOne(analysis);

		verify(sectionRepository).save(any());
		verify(analysis).succeed();
		verify(analysisRepository).save(analysis);
	}
}
