package com.bigproject.backend.domain.academicoperations.domain;

import java.util.UUID;

// 선택 회차의 검증 개념 표시 정의. 응시 기록이 없는 교육생도 개념 열 자체는 표시해야 하므로 개인 결과와 분리해 조회한다.
public record RoundConceptView(UUID conceptId, int sequenceNo, String conceptName) {
}
