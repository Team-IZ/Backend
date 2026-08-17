package com.bigproject.backend.domain.intervention.application;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 브리프 질문 근거를 <b>매니저가 읽는 말</b>로 바꾼다(32차 R6).
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@code questionRationale}은 스펙상 "매니저만 보는 근거"인데 실제 값이 개발자용이었다.
 *
 * <pre>
 * 문제 1 L3 인터뷰 (interviewSourceId 7d017b49-1095-49dd-a8c7-c8d135eb78ab) 기반 Q&amp;A 질문
 * PERSISTENT_LOW 위험 사유에 기반한 확인 질문
 * </pre>
 *
 * <p>{@code interviewSourceId}는 매니저에게 아무 뜻이 없고, {@code PERSISTENT_LOW}도 화면 어디에서도
 * 그 코드로 부르지 않는다 — 옆의 배지가 `지속 저점`이라고 말한다.
 *
 * <h2>왜 화면이 아니라 서버가 하나</h2>
 *
 * <p>프론트가 정규식으로 자르면 <b>어디까지가 개발자용인지 화면이 판정</b>하게 된다. 그리고 코드값을
 * 한국어로 옮기는 표가 화면에도 한 벌 생겨, 값이 늘 때 두 곳을 고쳐야 한다. 이 문장을 만든 쪽이
 * 정리하는 것이 맞다.
 *
 * <h2>지우지 않고 바꾼다</h2>
 *
 * <p>식별자 괄호만 <b>덜어내고</b> 코드는 <b>한국어로 치환</b>한다. 문장을 통째로 다시 쓰지 않는
 * 이유는 AI가 "왜 이 질문인지"를 설명한 부분이 매니저에게 쓸모 있기 때문이다 — 그 부분까지 버리면
 * 근거 열이 유형 이름만 남는다.
 *
 * <p>⚠️ 그래서 <b>AI 문장 구조에 기대는 처리</b>다. 모르는 형태가 오면 그 부분은 손대지 않고
 * 그대로 남는다(잘라내기가 아니라 치환이라 문장이 깨지지는 않는다). 근본 해결은 AI가 애초에 내부
 * 식별자를 문장에 넣지 않는 것이고, 그건 AI 계약 쪽에서 따로 정리해야 한다.
 */
final class BriefRationaleText {

	/** {@code (interviewSourceId 7d01…)} 같은 괄호. 앞의 공백까지 함께 먹어 두 칸이 남지 않게 한다. */
	private static final Pattern SOURCE_ID = Pattern.compile(
			"\\s*\\(\\s*interviewSourceId\\s*[:=]?\\s*[0-9a-fA-F-]{8,36}\\s*\\)");

	/** 괄호 없이 맨몸으로 적힌 경우. 위 패턴이 못 잡은 것을 받는다. */
	private static final Pattern BARE_SOURCE_ID = Pattern.compile(
			"\\s*interviewSourceId\\s*[:=]?\\s*[0-9a-fA-F-]{8,36}");

	/**
	 * 위험 사유 코드 → 화면이 배지에 쓰는 말.
	 *
	 * <p>{@code ck_interview_candidate_reason_reason_code}의 5종이다. 값이 늘면 여기만 고친다.
	 */
	private static final Map<String, String> REASON_NAMES = Map.of(
			"STAGE_DECLINE", "단계 하락",
			"PERSISTENT_LOW", "지속 저점",
			"INVALID_ATTEMPT", "무효 응시",
			"CONTRIBUTION_UNDERSTANDING_GAP", "기여 대비 이해도 격차",
			"LOW_PARTICIPATION", "저기여");

	/**
	 * 축 코드 → 단계 이름.
	 *
	 * <p>{@code EvaluationQueryRepository}가 적어 둔 순서 그대로다 —
	 * 코드이해 → 설계논리 → 대안비교 → 반례대응.
	 */
	private static final Map<String, String> AXIS_NAMES = Map.of(
			"L1", "코드 이해",
			"L2", "설계 논리",
			"L3", "대안 비교",
			"L4", "반례 대응");

	/** {@code 문제 3} · {@code 문항 3} → {@code 3번 문항}. 화면이 문항을 그렇게 부른다. */
	private static final Pattern PROBLEM_NO = Pattern.compile("문(?:제|항)\\s*(\\d+)");

	/** 축 코드가 낱말로 서 있을 때만 바꾼다 — {@code L1}이 다른 뜻으로 쓰인 문장을 건드리지 않는다. */
	private static final Pattern AXIS_CODE = Pattern.compile("\\bL([1-4])\\b");

	private BriefRationaleText() {
	}

	/**
	 * @param rationale AI가 만든 근거 문장. {@code null}이면 그대로 {@code null}
	 * @return 매니저가 읽을 수 있는 문장
	 */
	static String humanize(String rationale) {
		if (rationale == null || rationale.isBlank()) {
			return rationale;
		}

		String text = SOURCE_ID.matcher(rationale).replaceAll("");
		text = BARE_SOURCE_ID.matcher(text).replaceAll("");

		for (Map.Entry<String, String> reason : REASON_NAMES.entrySet()) {
			text = text.replace(reason.getKey(), reason.getValue());
		}

		text = AXIS_CODE.matcher(text).replaceAll(match -> AXIS_NAMES.get("L" + match.group(1)));
		text = PROBLEM_NO.matcher(text).replaceAll("$1번 문항");

		// 식별자를 덜어낸 자리에 생긴 빈칸·빈 괄호를 정리한다.
		text = text.replaceAll("\\(\\s*\\)", "");
		text = text.replaceAll("\\s{2,}", " ");
		return text.trim();
	}
}
