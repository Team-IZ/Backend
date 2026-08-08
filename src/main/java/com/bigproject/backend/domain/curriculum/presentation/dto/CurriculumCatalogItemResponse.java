package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository.CurriculumCatalogRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 교안 한 건(9차 R8). 목록 항목과 단건 상세가 <b>같은 이 스키마</b>를 쓴다 —
 * 목록에서 상세로 넘어갈 때 화면이 다루는 모양이 바뀌지 않고, 주소로 바로 들어와도 같은 값을 받는다.
 *
 * <p>값은 그 교안의 <b>최신 버전</b> 기준이다. 화면이 교안을 파일 하나로 다루기 때문이다
 * (버전은 같은 자리의 새 파일이지 별도 항목이 아니다).
 */
@Schema(name = "CurriculumCatalogItem", description = "교안 한 건(최신 버전 기준)")
public record CurriculumCatalogItemResponse(
        @Schema(description = "교안 ID. 버전이 바뀌어도 유지되는 안정 식별자이며 상세·섹션·회차 조회에 쓴다")
        UUID materialId,

        @Schema(description = "최신 교안 버전 ID. 프로젝트에 연결할 때(`POST /projects/{id}/curricula`) 보내는 값")
        UUID versionId,

        @Schema(description = "교안 제목(등록 시 입력한 이름)", example = "스프링 백엔드 심화", nullable = true)
        String title,

        @Schema(description = "업로드된 파일명. 이름 열에 그린다", example = "spring_backend_v1.pdf")
        String originalFileName,

        @Schema(description = "최신 버전 번호", example = "1") Integer versionNo,

        @Schema(description = "페이지 수. 분석 전이거나 확정되지 않았으면 null", example = "84", nullable = true)
        Integer pageCount,

        @Schema(description = """
                가장 최근 분석 **시도**의 상태. `분석 중`·`분석 완료`·`분석 실패` 배지의 근거다.
                **한 번도 분석하지 않은 교안은 null**이다 — 실패와 구분해야 해서 값을 만들어 넣지 않는다.
                `POST /curricula/{materialId}/analyses`로 재분석을 건 뒤 이 값을 폴링하면 된다.""",
                nullable = true)
        CurriculumAnalysisStatus analysisStatus,

        @Schema(description = "가장 최근 **성공한** 분석이 만든 섹션 수. 성공 분석이 없으면 0", example = "12")
        long sectionCount,

        @Schema(description = "최신 버전의 승인된(ACTIVE) 개념 수. 화면의 `12섹션 · 개념 48건`에서 뒤 숫자", example = "48")
        long conceptCount,

        @Schema(description = """
                이 교안(모든 버전)을 연결한 **삭제되지 않은** 회차 수. 화면의 `3개 회차에서 사용 중`이며
                삭제·교체 판단 근거다. 0이면 아무 회차도 쓰고 있지 않다.""", example = "3")
        long usedProjectCount,

        @Schema(description = "최신 버전 업로드 시각") Instant uploadedAt,

        @Schema(description = "올린 사람 이름. 계정이 지워졌으면 null", example = "김오퍼레이터", nullable = true)
        String uploadedByName
) {
    public static CurriculumCatalogItemResponse from(CurriculumCatalogRow row) {
        return new CurriculumCatalogItemResponse(
                row.materialId(),
                row.versionId(),
                row.title(),
                row.originalFileName(),
                row.versionNo(),
                row.pageCount(),
                row.analysisStatus(),
                row.sectionCount(),
                row.conceptCount(),
                row.usedProjectCount(),
                row.uploadedAt(),
                row.uploadedByName());
    }
}
