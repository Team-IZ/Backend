package com.bigproject.backend.domain.codeanalysis.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * DB CHECK: {@code ck_analysis_job_failure_code_2} — 12종.
 *
 * <p>세 계열이 한 컬럼에 모여 있다. 저장소 접근 주체가 AI 서버로 확정되면서 저장소 실패를 별도 필드로
 * 받지 않기로 했고(S-03), ZIP 내용 검증도 같은 이유로 합쳤다(S-15).
 *
 * <p>문자열 대신 enum으로 받는 이유는 <b>실패를 빨리 드러내기 위해서다.</b> AI가 값 집합 밖의 코드를
 * 보내면 문자열은 INSERT 시점의 CHECK 위반으로 터지는데, 그때는 이미 트랜잭션 한복판이라 원인이
 * 스택트레이스에 묻힌다. {@link #parse}는 응답을 읽는 자리에서 바로 걸러낸다.
 *
 * <h2>🔴 이 목록은 DB CHECK와 <b>정확히 같아야 한다</b> (2026-08-16)</h2>
 *
 * <p>enum에만 있고 CHECK에 없는 값이 생기면 {@link #parse}가 통과시켜 버려서, 위에서 말한
 * "빨리 드러내기"가 정확히 무너진다 — {@code AnalysisBatchService}의 {@code MODEL_ERROR} 대체 가드는
 * {@code parse}가 <b>비어 돌아올 때만</b> 작동하므로 그런 값은 가드를 그냥 지나 CHECK 위반으로 터진다.
 *
 * <p>이 목록은 <b>한 번도 CHECK와 일치한 적이 없었다.</b> 2026-08-07 최초 커밋(6656eb0)이 세 컬럼의
 * 값 집합을 여기 하나로 합치면서(S-03·S-15) 15종으로 시작했는데, 그 합의를 DB에 반영하는
 * 마이그레이션이 함께 나가지 않았다. javadoc 첫 줄이 "CHECK는 15종"이라고 적고 있었으나 그 CHECK는
 * 그때도 11종이었다 — <b>합의를 이미 이루어진 사실처럼 적은 것</b>이 아홉 날 뒤 28차 R1에서
 * 드러났다. 고친 것이 {@code docs/migration/2026-08-16_fix_analysis_job_failure_code_set.sql}이고,
 * {@code AnalysisFailureCodeContractTest}가 DDL 정본과 이 목록을 대조해 다시 갈라지지 않게 막는다.
 *
 * <h2>여기 없는 것 — {@code FILE_TOO_LARGE}·{@code ARCHIVE_INVALID}·{@code PROHIBITED_FILE}</h2>
 *
 * <p>ZIP <b>파일 자체</b>의 문제라 이 컬럼에 도달할 경로가 없다. 업로드 시점에
 * {@code SubmissionService#validateArchive}가 400·413으로 거절하고 {@code submission} 행조차 만들지
 * 않으므로 분석이 시작되지 않는다. {@code HttpAnalysisServerClient}도 저장된 ZIP을 못 읽었을 때
 * {@code ARCHIVE_INVALID}를 <b>일부러 피하고</b> {@link #TEMPORARY_ERROR}로 남긴다 — 우리 쪽 사정을
 * 교육생 잘못으로 기록하지 않기 위해서다. 값 집합에 남겨 두면 "왜 이 코드는 안 나오나"를 반복해서
 * 파게 되므로 뺐다. 그쪽 문구는 {@code SubmissionErrorCode}가 HTTP 응답으로 따로 낸다.
 *
 * <p>이 셋이 여기 있었던 이유는 {@code ck_submission_artifact_validation_failure_code}(5종)를 통째로
 * 합쳤기 때문이다. 그런데 <b>그 CHECK는 실 DB에 존재하지 않고</b>(선언은 DDL 정본에만 있다)
 * {@code submission_artifact.validation_failure_code}에 값을 넣는 코드도 없다 — 합칠 대상 자체가
 * 비어 있었다.
 */
public enum AnalysisFailureCode {

	// ── 분석 실행 실패 ──
	SOURCE_UNREACHABLE,
	UNSUPPORTED_LANGUAGE,
	ANALYSIS_TIMEOUT,
	MODEL_ERROR,
	TEMPORARY_ERROR,

	// ── 저장소 접근 실패. repository_verification.failure_code 와 문자열이 같다 ──
	INVALID_REPOSITORY_URL,
	REPO_NOT_FOUND,
	REPOSITORY_ACCESS_DENIED,
	BRANCH_NOT_FOUND,
	UNSUPPORTED_HOST,

	// ── ZIP 내용 검증 실패. AI가 분석 중에 판정해 돌려준다 ──
	//    파일 자체의 문제(크기·압축 형식·금지 파일)는 여기 없다 — 클래스 javadoc 참고.
	/**
	 * ZIP에 분석할 코드가 없다는 뜻이다. <b>분석이 근거를 못 찾은 경우에도 이 값을 쓴다.</b>
	 * 종전에 그 용도로 있던 {@code EMPTY_CODE_EVIDENCE}는 이름이 한 단어 차이라 바꿔 써도 CHECK가
	 * 둘 다 통과시켜 조용히 틀린 사유가 쌓이므로 2026-08-07에 값 집합에서 뺐다. 개념별로 근거를
	 * 찾지 못한 것은 {@code assessment_problem}의 NOT_GENERATED 슬롯이 따로 기록한다.
	 */
	EMPTY_CODE,
	GIT_LOG_MISSING;

	/**
	 * 같은 제출을 그대로 다시 분석해서 풀릴 수 있는 실패인가.
	 *
	 * <p><b>구분의 실익.</b> 나머지 8종은 저장소 URL이 틀렸거나 제출물 자체에 문제가 있는 경우라
	 * 같은 제출을 재분석해 봐야 결과가 같다. 그쪽의 복구 경로는 재시도가 아니라 <b>교육생의
	 * 재제출</b>이고, 재제출은 새 {@code submission} 행을 만들므로 분석이 자연히 다시 걸린다.
	 * 여기 4종만 우리 쪽 사정(AI 서버 장애·타임아웃·모델 오류·소스 일시 접근 실패)이라
	 * 교육생이 할 수 있는 일이 없다.
	 *
	 * <p>{@code MODEL_ERROR}를 넣은 것은 판단이 갈리는 지점이다. 공급자 일시 오류가 대부분이지만
	 * 계약 위반(AI가 UUID 아닌 jobId를 주는 등)도 이 코드로 접히므로, 후자면 재시도가 통째로
	 * 낭비된다. 재시도 상한({@code ai.analysis.max-attempts})으로 손실을 묶어 두고 넣었다 —
	 * 빼면 공급자 blip 한 번에 분석이 영구 실패한다.
	 *
	 * <p>🔴 이 값 집합은 {@code AnalysisDispatchRepository}의 두 네이티브 쿼리에 문자열로도
	 * 들어가 있다. 한쪽만 고치면 조용히 어긋나므로 반드시 함께 고친다.
	 */
	public boolean retryable() {
		return this == TEMPORARY_ERROR
				|| this == ANALYSIS_TIMEOUT
				|| this == MODEL_ERROR
				|| this == SOURCE_UNREACHABLE;
	}

	/** 값 집합 밖이면 비어 있는 결과를 준다. 호출부가 "모르는 코드"를 어떻게 다룰지 정하게 한다. */
	public static Optional<AnalysisFailureCode> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String trimmed = raw.trim();
		return Arrays.stream(values()).filter(code -> code.name().equals(trimmed)).findFirst();
	}
}
