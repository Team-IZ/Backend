package com.bigproject.backend.domain.projectexecution.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 프로젝트 실행(구성·일정·요구사항·교안 연결·검증개념) API가 내려보내는 도메인 에러 코드.
 *
 * <p><b>왜 만드는가.</b> 이 도메인의 실패는 전부 {@code NOT_FOUND}·{@code BAD_REQUEST}·{@code CONFLICT}
 * 셋 중 하나로만 나가고 있었다. 상태 코드에 이미 있는 정보라 화면이 새로 알 수 있는 것이 없다 —
 * 특히 교안 연결(교안 연결 POST 오퍼레이션)은 한 오퍼레이션 안에서
 * <em>프로젝트가 없다</em>·<em>교안 버전이 없다</em>가 같은 404로, <em>빅프로젝트다</em>·
 * <em>성공한 분석이 없다</em>·<em>승인된 매핑이 없다</em>가 같은 400으로 나갔다.
 * 사용자가 해야 할 일이 각각 다른데 화면은 문장 하나로 뭉칠 수밖에 없었다.
 *
 * <p><b>남의 도메인 코드를 새로 만들지 않는다.</b> 교안 쪽 실패는
 * {@code CurriculumErrorCode}(CURRICULUM_VERSION_NOT_FOUND 등)를 그대로 쓰고, 접근 통제 실패는
 * {@code AnalyticsErrorCode}를 쓴다. 뜻이 같은 코드를 도메인마다 새로 만들면 프론트 유니온에
 * 동의어만 늘고 카탈로그에서 이름이 겹친다.
 */
public enum ProjectExecutionErrorCode implements ApiErrorCode {

    /** 그 기관에 그 프로젝트가 없다. 다른 기관의 프로젝트도 여기로 온다 — 존재 여부는 알려 주지 않는다. */
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."),

    /** 같은 기수에 같은 이름의 프로젝트가 이미 있다. 화면은 이름 입력란에 인라인 오류를 띄운다. */
    PROJECT_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 프로젝트명입니다."),

    /**
     * 빅프로젝트에 교안을 연결하려 했다. project_curriculum DDL이 미니프로젝트만 허용한다 —
     * 재시도해도 같으므로 화면은 교안 연결 UI 자체를 감춰야 한다.
     */
    CURRICULUM_NOT_APPLICABLE_TO_BIG_PROJECT(HttpStatus.BAD_REQUEST, "빅프로젝트에는 교안을 연결할 수 없습니다."),

    /** 연결 대상 버전에 성공한 분석이 없다. 화면이 할 일은 재분석 요청을 안내하는 것이다. */
    CURRICULUM_ANALYSIS_NOT_SUCCEEDED(HttpStatus.BAD_REQUEST, "성공한 분석이 없는 교안 버전입니다."),

    /** 분석은 됐는데 승인된(ACTIVE) 개념 매핑이 하나도 없다. 화면은 개념 승인 화면으로 보낸다. */
    CURRICULUM_MAPPING_NOT_APPROVED(HttpStatus.BAD_REQUEST, "승인된 개념 매핑이 없는 교안 버전입니다."),

    /**
     * 이미 연결된 교안 버전이다. <b>낙관적 잠금 충돌이 아니라 중복 연결</b>이며, 화면은 새로고침이 아니라
     * "이미 연결됨"을 알리고 목록에서 그 항목을 비활성화한다 — 8차 요청이 지적한, 409 하나로는
     * 가릴 수 없던 두 경우 중 하나다.
     */
    CURRICULUM_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결된 교안 버전입니다."),

    /** 확정하려는 매핑 ID가 원장에 없다. 후보 목록이 낡았다는 뜻이라 화면은 후보를 다시 읽어야 한다. */
    CONCEPT_MAPPING_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 매핑입니다."),

    /** 빅프로젝트에는 "미프 N차" 회차 라벨이 없다. */
    BIG_PROJECT_HAS_NO_ROUND_LABEL(HttpStatus.BAD_REQUEST, "빅프로젝트에는 회차 라벨이 없습니다."),

    /**
     * 회차 번호를 계산하지 못했다. 미니프로젝트 목록에 자기 자신이 없다는 뜻이라 데이터 정합성 결함이다 —
     * 사용자가 할 수 있는 일이 없으므로 5xx로 낸다.
     */
    ROUND_NUMBER_UNRESOLVED(HttpStatus.INTERNAL_SERVER_ERROR, "회차 번호를 계산할 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ProjectExecutionErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}