package com.bigproject.backend.domain.submission.domain;

import java.util.UUID;

/**
 * GitHub 제출이 접수됐다는 사실을 알린다.
 *
 * <p>2026-08-07 결정으로 코드 분석 트리거가 "마감 후 배치"에서 "제출 즉시"로 바뀌었다. 팀이 마감 전
 * 재제출할 때마다 AI 호출이 다시 일어나는 비용을 감수하는 대신, 저장소 URL 오타 같은 실수를 마감 전에
 * 알고 고칠 수 있게 한다(P-09 해소).
 *
 * <p>ZIP 제출은 이 이벤트를 발행하지 않는다. AI 서버가 ZIP을 받을 방법이 아직 없어 제출 자체가
 * {@code deprecated}다.
 *
 * <p>{@code submission} 모듈이 발행하고 {@code codeanalysis} 모듈이 듣는다. 제출 쪽이 분석 트리거
 * 규칙(재시도 제한·비용 통제 등)을 알 필요가 없도록 이벤트로 경계를 나눈다.
 */
public record SubmissionAcceptedEvent(UUID submissionId) {
}
