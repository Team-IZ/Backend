package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;

import java.util.List;
import java.util.UUID;

/**
 * curriculum 도메인의 조회 서비스. 지금은 project 도메인이 기다리는 두 가지만 있다 —
 * ① 프로젝트 생성/구성 화면에서 "연결할 교안 후보" 목록, ② 연결 시점 단건 검증.
 *
 * <p>⚠ curriculum_material/version에는 cohort_id가 없다 — org_id 범위로만 조회한다.
 * 컨트롤러가 cohortId를 orgId로 바꿔서 넘겨야 한다(Cohort 조회 또는 actor context).
 */
public interface CurriculumService {

    /**
     * 연결 가능한 교안 버전 목록(기관 범위, 활성 버전만, 논리 삭제된 교안 제외).
     * OP-03 생성 모달·OP-04 구성 탭의 "교안 선택" 드롭다운이 쓴다.
     */
    List<CurriculumVersion> findLinkableCurricula(UUID orgId);

    /**
     * 단건 조회 + 테넌트 검증. project 도메인의 {@code linkCurriculum}이 연결 전에 호출해서
     * "이 orgId가 정말 이 버전의 주인인가"를 확인하는 용도.
     *
     * @throws com.bigproject.backend.domain.curriculum.domain.CurriculumException
     *         버전이 없거나 다른 기관 것이면 {@code CURRICULUM_VERSION_NOT_FOUND}
     */
    CurriculumVersion getLinkableCurriculum(UUID versionId, UUID orgId);
}