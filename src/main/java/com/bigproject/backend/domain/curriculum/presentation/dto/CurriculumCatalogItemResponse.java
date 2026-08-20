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
 * <p>값은 <b>기준 버전</b> 하나를 따른다(2026-08-20, 44차 R1 — 이전에는 "최신 버전"으로 고정이었다).
 * 목록(`GET .../curricula`)은 기준 버전이 <b>항상 최신</b>이라 예전과 동일하다. 상세
 * (`GET /curricula/{materialId}`)는 `?versionId=`를 주면 기준 버전이 그 버전으로 바뀐다 — 생략하면
 * 목록과 같은 최신 버전이다. 무엇이 기준 버전이든 `usedProjectCount`(교안 전체)만은 예외로 바뀌지
 * 않는다 — 삭제 가드와 같은 모집단을 유지해야 하기 때문이다.
 */
@Schema(name = "CurriculumCatalogItem", description = "교안 한 건(기준 버전 — 목록은 항상 최신, 상세는 `versionId`로 지정 가능)")
public record CurriculumCatalogItemResponse(
        @Schema(description = "교안 ID. 버전이 바뀌어도 유지되는 안정 식별자이며 상세·섹션·회차 조회에 쓴다")
        UUID materialId,

        @Schema(description = "기준 버전 ID(목록은 항상 최신, 상세는 `versionId`로 지정 가능). 프로젝트에 연결할 때(`POST /projects/{id}/curricula`) 보내는 값")
        UUID versionId,

        @Schema(description = "교안 제목(등록 시 입력한 이름)", example = "스프링 백엔드 심화", nullable = true)
        String title,

        @Schema(description = "업로드된 파일명. 이름 열에 그린다", example = "spring_backend_v1.pdf")
        String originalFileName,

        @Schema(description = "기준 버전 번호", example = "1") Integer versionNo,

        @Schema(description = "페이지 수. 분석 전이거나 확정되지 않았으면 null", example = "84", nullable = true)
        Integer pageCount,

        @Schema(description = """
                기준 버전의 가장 최근 분석 **시도** 상태. `분석 중`·`분석 완료`·`분석 실패` 배지의 근거다.
                **한 번도 분석하지 않은 버전은 null**이다 — 실패와 구분해야 해서 값을 만들어 넣지 않는다.
                `POST /curricula/{materialId}/analyses`로 재분석을 건 뒤 이 값을 폴링하면 된다.""",
                nullable = true)
        CurriculumAnalysisStatus analysisStatus,

        @Schema(description = "기준 버전의 가장 최근 **성공한** 분석이 만든 섹션 수. 성공 분석이 없으면 0", example = "12")
        long sectionCount,

        @Schema(description = "기준 버전의 승인된(ACTIVE) 개념 수. 화면의 `12섹션 · 개념 48건`에서 뒤 숫자", example = "48")
        long conceptCount,

        @Schema(description = """
                이 교안(모든 버전)을 연결한 **삭제되지 않은** 회차 수. 화면의 `3개 회차에서 사용 중`이며
                삭제·교체 판단 근거다. 0이면 아무 회차도 쓰고 있지 않다.

                **`GET /curricula/{materialId}?versionId=`로 옛 버전을 봐도 이 값은 바뀌지 않는다**
                (2026-08-20, 44차 R1) — 삭제 가드와 같은 모집단을 유지해야 하기 때문이다. 그 버전만의
                개수는 `versionUsedProjectCount`를 쓴다.""", example = "3")
        long usedProjectCount,

        @Schema(description = """
                이 행이 대표하는 **그 버전만** 연결한 삭제되지 않은 회차 수(2026-08-20, 44차 R1).
                목록에서는 최신 버전만의 개수이고, `?versionId=`로 옛 버전 상세를 열면 그 버전만의
                개수다. `usedProjectCount`(교안 전체)와 다른 모집단이다 — 삭제 가능 여부는 여전히
                `usedProjectCount`로 판단한다.""", example = "1")
        long versionUsedProjectCount,

        @Schema(description = "기준 버전 업로드 시각") Instant uploadedAt,

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
                row.versionUsedProjectCount(),
                row.uploadedAt(),
                row.uploadedByName());
    }
}
