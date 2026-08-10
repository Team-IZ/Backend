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
     */
    CURRICULUM_ANALYSIS_NOT_COMPLETED(HttpStatus.SERVICE_UNAVAILABLE, "분석이 아직 완료되지 않았습니다."),

    /** 업로드 본문에 파일이 없다. 서버 상태가 아니라 요청 결함이므로 400이다 — 전에는 503으로 나갔다. */
    CURRICULUM_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "업로드할 파일이 없습니다."),

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