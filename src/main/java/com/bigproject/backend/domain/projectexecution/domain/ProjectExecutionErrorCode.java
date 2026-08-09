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

    /**
     * 한 번에 확정하는 목록에 같은 개념(teaches)이 두 번 들어왔다(10차 R1).
     *
     * <p>교안 두 벌이 같은 개념을 가르치면 후보 목록에 그 개념이 각각 다른 매핑으로 두 줄 나오는데,
     * 둘 다 고르면 {@code uq_project_verification_concept_concept_set_id_teaches_id}에 걸린다.
     * 그대로 두면 DB 제약 위반이 <b>정체를 알 수 없는 409</b>로 나가므로, 어떤 개념이 겹쳤는지
     * 이름을 담아 400으로 돌려준다 — 화면이 그 줄을 짚어 줄 수 있어야 한다.
     */
    CONCEPT_DUPLICATED(HttpStatus.BAD_REQUEST, "같은 개념을 두 번 확정할 수 없습니다."),

    /** 떼려는 교안 연결이 이 프로젝트에 없다. 이미 해제됐거나 다른 프로젝트의 연결 ID다. */
    CURRICULUM_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "교안 연결을 찾을 수 없습니다."),

    /**
     * 확정된 검증 개념이 그 교안에서 왔기 때문에 뗄 수 없다(9차 R4).
     * 출처가 끊긴 개념은 리포트가 교안 위치를 가리킬 수 없다 —
     * 화면이 할 일은 검증 개념을 먼저 다시 확정하도록 안내하는 것이다.
     */
    CURRICULUM_IN_USE_BY_CONCEPTS(HttpStatus.CONFLICT, "확정된 검증 개념이 사용 중인 교안입니다."),

    /**
     * 제출·응시가 붙은 회차라 지울 수 없다(9차 R4).
     *
     * <p>사유를 코드로 쪼개지 않은 것은 <b>화면이 할 일이 하나</b>이기 때문이다 — 어느 쪽이든
     * "지울 수 없습니다"를 보여주고 삭제 버튼을 잠근다. 무엇이 붙어 있는지는 {@code message}에 담는다.
     */
    PROJECT_NOT_DELETABLE(HttpStatus.CONFLICT, "이미 진행된 회차는 삭제할 수 없습니다."),

    /** 빅프로젝트에는 "미프 N차" 회차 라벨이 없다. */
    BIG_PROJECT_HAS_NO_ROUND_LABEL(HttpStatus.BAD_REQUEST, "빅프로젝트에는 회차 라벨이 없습니다."),

    /**
     * 회차 번호를 계산하지 못했다. 미니프로젝트 목록에 자기 자신이 없다는 뜻이라 데이터 정합성 결함이다 —
     * 사용자가 할 수 있는 일이 없으므로 5xx로 낸다.
     */
    ROUND_NUMBER_UNRESOLVED(HttpStatus.INTERNAL_SERVER_ERROR, "회차 번호를 계산할 수 없습니다."),

    /**
     * 확정 개념이 가리키는 출처 매핑이 없다. <b>정상 경로로는 생길 수 없다</b> —
     * {@code fk_project_verification_concept_source_mapping_id}가 ON DELETE RESTRICT라
     * 참조되는 동안 매핑이 지워지지 않기 때문이다(10차 Q2).
     *
     * <p>그래서 이름·페이지를 null로 채워 내보내는 대신 여기서 끊는다. 응답 타입이 non-null이라
     * null을 흘려보내면 화면이 빈칸을 그리고 원인은 한참 뒤에 드러난다 — DB가 규약 밖에서
     * 바뀐 것이므로 조용히 넘기지 않고 5xx로 알린다.
     */
    CONCEPT_SOURCE_MAPPING_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "확정 개념의 출처 매핑을 찾을 수 없습니다.");

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