package com.bigproject.backend.domain.curriculum.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 교안(등록·분석·섹션 조회) API가 내려보내는 도메인 에러 코드.
 *
 * <p><b>{@link ApiErrorCode}를 구현한다.</b> 전에는 이 enum이 아무 계약도 구현하지 않았고
 * {@code CurriculumException}도 {@code RuntimeException}만 상속해서, <b>이 예외를 잡는 어드바이스가
 * 어디에도 없었다</b> — 여기 골라 둔 상태(404·503)와 문구가 응답에 실리지 못하고 전부 500으로 나갔다.
 * 이제 {@code ApiExceptionHandler} 하나가 받아 {@code code}와 상태를 그대로 내보내고,
 * {@code SwaggerConfig} 카탈로그가 코드 목록을 스펙의 examples 키로 올린다.
 *
 * <p>"불러오지 못했다"를 뜻하던 {@code CURRICULUM_UNAVAILABLE} 하나에 세 상황이 뭉쳐 있었다 —
 * 분석 미완료(503)·업로드 파일 누락(400)·저장 파일 읽기 실패(503). 사용자가 할 일이 각각 다르고
 * 그중 하나는 상태 코드마저 틀렸으므로 갈라 둔다.
 */
public enum CurriculumErrorCode implements ApiErrorCode {

    /** project 도메인의 CURRICULUM_NOT_REGISTERED와 대응 — 이 도메인 안에서 버전을 못 찾았을 때. */
    CURRICULUM_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 교안입니다."),

    /** 교안 원장 자체가 없다. 버전 없음과 구분해야 화면이 "없는 교안"과 "아직 분석 전"을 가른다. */
    CURRICULUM_MATERIAL_NOT_FOUND(HttpStatus.NOT_FOUND, "교안을 찾을 수 없습니다."),

    /**
     * MG-09 케이스 계약 "불러오기 실패" — 최신 버전에 성공한 분석이 아직 없다.
     * 시간이 지나면 풀리는 상태라 화면은 재시도·새로고침을 안내한다(영구 실패인 404와 다르다).
     *
     * <h2>🔴 503에서 409로 내렸다(18차 R1)</h2>
     *
     * <p>"시간이 지나면 풀린다"를 상태 코드로 표현하려고 503을 골랐는데, <b>그 선택이 이
     * 응답을 화면에 도달하지 못하게 만들고 있었다.</b> 503은 "서버가 지금 요청을 처리할 수
     * 없다"는 <b>인프라 신호</b>라 경로 위의 모든 층이 그렇게 해석한다.
     *
     * <ul>
     *   <li>프론트 전역 재시도가 {@code status >= 500}을 조건으로 걸어 두어 <b>3회 자동 재시도</b>가 붙는다</li>
     *   <li>그 재시도가 동시에 나가면서 프록시의 동시 요청 결함(15차 R3)을 그대로 밟아
     *       {@code net::ERR_FAILED}가 된다 — 화면에는 <b>응답이 아예 없는 것처럼</b> 보인다</li>
     *   <li>App Runner·envoy 같은 중간 층도 업스트림 비정상으로 읽어 재시도·연결 종료 대상이 된다</li>
     * </ul>
     *
     * <p>실제로는 <b>요청이 지금 상태와 맞지 않는다</b>는 뜻이지 서버가 아픈 것이 아니다.
     * 409가 정확하고, 프론트가 요청서에서 직접 지목한 코드이기도 하다. 재시도해야 한다는
     * 사실은 상태 코드가 아니라 {@code code}로 전달한다 — 그게 이 enum이 있는 이유다.
     */
    CURRICULUM_ANALYSIS_NOT_COMPLETED(HttpStatus.CONFLICT, "분석이 아직 완료되지 않았습니다."),

    /**
     * 그 교안의 최신 버전에 <b>아직 끝나지 않은 분석</b>이 있다(25차 R2).
     *
     * <p>종전에는 {@code PENDING}·{@code RUNNING}인 교안에 재분석을 걸어도 그대로 202로 접수했다.
     * 운영자가 「분석 중」 화면에서 버튼을 세 번 누르면 <b>AI 분석이 세 번 걸렸다</b> — 화면은
     * 다이얼로그로 말릴 수는 있어도 잠글 수는 없었다(멈춘 분석을 푸는 유일한 출구가 그 버튼이라서).
     *
     * <p>{@code CURRICULUM_ANALYSIS_NOT_COMPLETED}와 상태 코드가 같지만 뜻이 반대다 —
     * 그쪽은 "결과를 아직 못 준다"(읽기), 이쪽은 "지금은 더 걸 수 없다"(쓰기)다.
     *
     * <p>멈춘 분석을 강제로 다시 돌려야 하면 {@code ?force=true}로 이 검사를 건너뛴다.
     */
    CURRICULUM_ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "이 교안은 이미 분석 중입니다."),

    /**
     * 삭제하려는 교안을 <b>쓰고 있는 회차가 있다</b>(25차 R11).
     *
     * <p>연결을 남긴 채 지우면 그 회차의 문항이 근거로 삼는 교안이 목록에서 사라진다.
     * 화면은 이 코드를 받으면 「어느 회차가 쓰는지」를 이미 보여 줄 수 있다
     * ({@code GET /curricula/{materialId}/projects}).
     */
    CURRICULUM_MATERIAL_IN_USE(HttpStatus.CONFLICT, "회차에 연결된 교안은 삭제할 수 없습니다."),

    /** 업로드 본문에 파일이 없다. 서버 상태가 아니라 요청 결함이므로 400이다 — 전에는 503으로 나갔다. */
    CURRICULUM_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "업로드할 파일이 없습니다."),

    /**
     * 같은 기관에 <b>같은 제목의 교안이 이미 있다</b>(22차 R2).
     *
     * <p>{@code uq_curriculum_material_org_id_normalized_title}가 <b>부분 인덱스가 아니라 전역
     * UNIQUE</b>라, 논리 삭제된 교안도 제목을 계속 점유한다. 종전에는 이 충돌이 그대로 DB까지
     * 내려가 {@code DataIntegrityViolationException} → <b>코드 없는 500</b>으로 나갔다.
     * 스펙에도 없는 상태였고, 화면은 제목 입력란에 인라인 오류를 띄울 근거가 없었다.
     *
     * <p>파일 크기와 무관하게 나므로 "50KB짜리도 500"이라는 증상의 한 축이었다.
     * 회차 이름의 {@code PROJECT_NAME_DUPLICATED}와 같은 성격·같은 상태 코드다.
     */
    CURRICULUM_TITLE_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 교안 제목입니다."),

    /**
     * 업로드한 파일을 <b>저장하지 못했다</b>(22차 R2).
     *
     * <p>저장 경로가 쓸 수 없는 상태일 때 난다 — 배포 환경의 파일시스템이 읽기 전용이거나
     * 디스크가 찼을 때다. 종전에는 {@code UncheckedIOException}이 그대로 올라가
     * <b>코드 없는 500</b>이 됐다.
     *
     * <p>사용자가 할 수 있는 일이 재시도뿐이고 시간이 지나면 풀릴 수 있어
     * {@link #CURRICULUM_FILE_UNREADABLE}과 같은 성격의 503이다.
     */
    CURRICULUM_FILE_STORE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "업로드한 파일을 저장하지 못했습니다."),

    /**
     * 분석 상태 필터와 `분석 전`만 보기를 함께 걸었다(13차 R2).
     *
     * <p>둘은 서로를 배제한다 — `분석 전`은 분석 상태가 <b>없는</b> 교안이라 어떤 상태로도 좁혀지지
     * 않는다. 빈 목록을 조용히 돌려주면 화면이 "그런 교안이 없다"로 읽으므로 입력 오류로 끊는다.
     * 명단의 {@code ROSTER_FILTER_CONFLICT}(반 필터 + 미배정 필터)와 같은 성격이다.
     */
    CURRICULUM_FILTER_CONFLICT(HttpStatus.BAD_REQUEST, "상태 필터와 분석 전 필터는 함께 지정할 수 없습니다."),

    /**
     * 저장된 파일을 읽지 못했다. 사용자가 할 수 있는 일이 없고 재시도로 풀릴 수 있어 503이다.
     *
     * <p>지금은 {@code readFileBytes}가 더미 바이트를 돌려주는 임시 우회 상태라 <b>실제로는 나가지 않는다</b> —
     * S3 자격증명이 붙어 원래 로직으로 되돌리는 순간 살아난다. 그래서 스펙의 응답 목록에는 아직 적지 않았다.
     * 되살릴 때 {@code requestAnalysis}에 503을 함께 문서화한다.
     */
    CURRICULUM_FILE_UNREADABLE(HttpStatus.SERVICE_UNAVAILABLE, "저장된 파일을 읽을 수 없습니다."),

    /**
     * AI 프록시를 깨우지 못해 교안을 보내지 못했다(2026-08-11).
     *
     * <p>전에는 웜업 실패가 전송 계층 예외 그대로 올라가 코드 없는 500으로 나갔다. 사용자가 할 수
     * 있는 일이 재시도뿐이라는 점에서 {@code CURRICULUM_FILE_UNREADABLE}과 같은 성격의 503이다.
     */
    CURRICULUM_AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없어 지금은 분석을 요청할 수 없습니다."),

    /** 위 셋으로 갈라지지 않는 나머지 "불러오기 실패". 새 호출부는 가급적 구체 코드를 쓴다. */
    CURRICULUM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "불러오지 못했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    CurriculumErrorCode(HttpStatus status, String defaultMessage) {
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