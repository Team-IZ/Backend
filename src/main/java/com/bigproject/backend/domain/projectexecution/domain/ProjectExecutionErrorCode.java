package com.bigproject.backend.domain.projectexecution.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 프로젝트 실행(구성·일정·요구사항·교안 연결·검증개념·팀 편성) API가 내려보내는 도메인 에러 코드.
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

    /** 확정하려는 매핑 ID가 이미 확정된 검증 개념 목록에 있다. */
    CONCEPT_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 확정된 개념입니다."),

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
    CONCEPT_SOURCE_MAPPING_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "확정 개념의 출처 매핑을 찾을 수 없습니다."),

    // ── 팀 편성(MG-08) ────────────────────────────────────────────────────

    /** 그 프로젝트·기관에 그 팀이 없다. 해체된 팀도 여기로 온다. */
    TEAM_NOT_FOUND(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다."),

    /**
     * 배정하려는 사람이 이 프로젝트의 참여자(project_membership)가 아니다.
     * 다른 반·다른 프로젝트 사람을 팀에 넣으려 한 경우가 여기로 온다.
     */
    PROJECT_MEMBERSHIP_NOT_FOUND(HttpStatus.BAD_REQUEST, "이 프로젝트의 참여자가 아닙니다."),

    /** 팀 안에 그 사람이 없다(이미 빠졌거나 애초에 없었음). 팀원 제외 요청에서 난다. */
    TEAM_MEMBERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "그 팀에 속한 인원이 아닙니다."),

    /**
     * 요청이 지정한 반을 그 매니저가 맡고 있지 않다. 남의 반 팀을 만들거나 남의 반 팀에
     * 손대려는 요청이 여기로 온다.
     *
     * <p>여기 있던 {@code MANAGER_CLASSROOM_AMBIGUOUS}(400)를 대체한다. 그쪽은 요청에 반이 없어
     * 서버가 담당 반을 역산하던 시절의 것으로, "매니저는 기수당 반 하나만 담당한다"는 전제가
     * 깨지면 아무것도 못 하게 막는 코드였다. 반을 요청이 정하게 되면서 그 전제와 함께 사라졌다.
     */
    CLASS_NOT_MANAGED(HttpStatus.FORBIDDEN, "담당하지 않는 반입니다."),

    /** 요청이 지정한 반이 없거나 이 프로젝트의 기수에 속하지 않는다. */
    CLASS_NOT_FOUND(HttpStatus.NOT_FOUND, "이 프로젝트의 기수에 그 반이 없습니다."),

    /**
     * 그 반에 같은 이름의 팀이 이미 있다({@code uq_team_project_id_class_id_name}).
     *
     * <p>종전에는 이 충돌이 그대로 DB까지 내려가 {@code DataIntegrityViolationException} →
     * 전역 처리기의 fallback({@code DATA_INTEGRITY_VIOLATION}, "요청을 처리할 수 없습니다.
     * 데이터 제약 조건에 맞지 않습니다.")으로 새 나갔다. 화면은 <b>무엇이 잘못됐는지 말할 수
     * 없어</b> 그 문구를 그대로 띄웠고, 매니저는 이름을 바꿔 보면 된다는 것을 알 수 없었다.
     *
     * <p>이름은 매니저가 입력하는 값이므로 <b>고칠 수 있는 실수</b>다. 코드가 그렇게 말해야 한다.
     */
    TEAM_NAME_DUPLICATED(HttpStatus.CONFLICT, "그 반에 같은 이름의 팀이 이미 있습니다."),

    /**
     * 그 반에 같은 번호의 팀이 이미 있다({@code uq_team_project_id_class_id_team_number}).
     *
     * <p>번호는 <b>서버가 매기므로</b> 매니저가 고칠 수 있는 값이 아니다 — 같은 반에 동시에
     * 팀을 만들어 번호가 겹친 경우가 대부분이고, 다시 시도하면 다음 번호를 받는다.
     * {@link #TEAM_NAME_DUPLICATED}와 나눠 둔 이유가 그것이다: 한쪽은 입력을 고치라는 말이고
     * 다른 한쪽은 다시 눌러 보라는 말이라, 화면이 같은 문구를 띄우면 안 된다.
     */
    TEAM_NUMBER_DUPLICATED(HttpStatus.CONFLICT, "그 반에 같은 번호의 팀이 이미 있습니다. 다시 시도해 주세요."),

    /**
     * 자동 배분은 <b>그 반에</b> 팀이 하나도 없을 때만 된다(정의 문서). 이미 팀이 있으면 여기로 온다.
     *
     * <p>판정 범위가 프로젝트 전역이던 때는 <b>다른 반 매니저가 먼저 팀을 만들면 내 반이 막혔다</b> —
     * 미프 5차는 J반에만 시드 팀 6개가 있어서 B·D반 매니저도 이 코드를 받았다.
     */
    AUTO_ASSIGN_NOT_ALLOWED(HttpStatus.CONFLICT, "그 반에 이미 팀이 편성되어 있어 자동 배분을 실행할 수 없습니다."),

    /** 그 반에 배분할 미배정 인원이 없다. */
    NO_MEMBERS_TO_ASSIGN(HttpStatus.BAD_REQUEST, "그 반에 배분할 인원이 없습니다."),

    /** 확정하려는데 그 반에 팀이 하나도 없다. */
    NO_TEAMS_TO_CONFIRM(HttpStatus.BAD_REQUEST, "그 반에 확정할 팀이 없습니다."),

    /**
     * 그 반에 미배정 인원이 남아있는 채로 확정하려 했다. 전원 배정이 되어야 확정할 수 있다(③→④).
     *
     * <p>판정 범위가 프로젝트 전역이던 때는 <b>다른 반에 미배정이 남으면 내 반 확정이 막혔다.</b>
     */
    TEAMS_NOT_READY(HttpStatus.BAD_REQUEST, "그 반에 아직 팀에 들어가지 않은 인원이 있어 확정할 수 없습니다."),

    /**
     * 이 팀이 이미 정상 접수된 제출을 했다. 제출된 코드가 팀 구성에 묶여 있어 배정을 바꿀 수 없다
     * (정의 문서 ⑤ "제출 시작됨: [팀 이동]만 남는다 — 되돌릴 수 없다").
     */
    TEAM_SUBMISSION_LOCKED(HttpStatus.CONFLICT, "이미 제출한 팀은 팀 구성을 바꿀 수 없습니다."),

    /**
     * 확정된(CONFIRMED) 팀은 구성을 바꿀 수 없다(정의 문서 "확정하면 학생들이 코드를 제출할 수
     * 있게 되고, 팀은 잠깁니다"). [편성 다시 열기]로 DRAFT로 되돌린 뒤에만 편집할 수 있다.
     */
    TEAM_CONFIRMED_LOCKED(HttpStatus.CONFLICT, "확정된 팀은 편성 다시 열기 후에만 바꿀 수 있습니다."),

    /** 목록 조회 시 cohort와 classId 중 정확히 하나만 지정해야 하는데 둘 다 없거나 둘 다 있다. */
    PROJECT_LIST_SCOPE_AMBIGUOUS(HttpStatus.BAD_REQUEST, "cohort와 classId 중 정확히 하나를 지정해야 합니다.");

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