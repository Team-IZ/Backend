package com.bigproject.backend.domain.curriculum.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 교안 분석 실행 상태.
 *
 * <p>교안 목록·상세의 {@code analysisStatus}가 이 값을 그대로 내려준다(9차 R8).
 * {@code POST /curricula/{materialId}/analyses}로 재분석을 걸어 두고 결과를 확인할 방법이 없어
 * 화면이 폴링할 대상이 없던 문제가 이 값으로 풀린다.
 */
@Schema(
        name = "CurriculumAnalysisStatus",
        description = """
                교안 분석 실행 상태.
                `PENDING`(대기) · `RUNNING`(진행 중) → 화면의 `분석 중`,
                `SUCCEEDED` → `분석 완료`, `FAILED` → `분석 실패`.
                한 번도 분석하지 않은 교안은 이 값 자체가 null이다.""",
        enumAsRef = true
)
public enum CurriculumAnalysisStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED
}
