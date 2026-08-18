package com.bigproject.backend.domain.intervention.domain;

import java.util.regex.Pattern;

/**
 * 위험 판정 근거 문구에서 <b>대괄호 태그를 걷어낸다</b>(32차 R4).
 *
 * <h2>왜 아직도 필요한가</h2>
 *
 * <p>30차 R8에서 <b>생성 코드는 이미 고쳤다</b> — 지금 판정 배치가 만드는 문장에는 태그가 없다
 * ({@code JdbcRiskOutcomeBatchRepository.ENROLL_REASON_SQL}). 그런데 그때 회신에 적었듯이
 * <b>이미 적재된 시드는 그 PR로 바뀌지 않았고</b>, 실서버에서 계속 보인다는 확인을 32차 R4로 받았다.
 *
 * <pre>
 * [SEVERE] 최근 2개 유효 회차 점수 1.67 → 1.67 (기수 평균 대비 저점)
 * [WARN] 응시 기간 종료까지 세션을 시작하지 않음
 * </pre>
 *
 * <h2>UPDATE와 이것을 함께 한다</h2>
 *
 * <p>데이터 정리(UPDATE)는 운영 DB에 접근해야 하고 언제 돌지 정해지지 않는다. 이 정제는
 * <b>배포만으로 즉시</b> 듣는다. 둘은 배타적이지 않다 — UPDATE가 돌면 이 함수는 아무것도
 * 하지 않게 되고, 그 뒤에 옛 형식이 어디선가 다시 들어와도 화면에는 새지 않는다.
 *
 * <p>화면 쪽 정규식과도 겹쳐도 무해하다. 태그가 없는 문장에는 아무 일도 일어나지 않는다.
 *
 * <h2>맨 앞의 태그 하나만 본다</h2>
 *
 * <p>문장 중간의 대괄호는 건드리지 않는다. 판정 근거가 코드나 값을 대괄호로 인용하는 형식을
 * 나중에 쓸 수 있고, 그때 이 함수가 뜻을 지우면 안 된다.
 */
public final class RiskSummaryText {

	/**
	 * 문장 맨 앞의 {@code [SEVERE]}·{@code [WARN]}·{@code [RISK]}와 뒤따르는 공백.
	 *
	 * <p>34차 R5로 {@code RISK}를 더했다. 32차 R4 때 이 목록을 만들면서 실서버에서 관측된 두
	 * 종류만 넣었는데, 시드에는 <b>세 번째가 있었다</b> — 원장 전수로 {@code [SEVERE]} 69건 ·
	 * {@code [WARN]} 56건 · {@code [RISK]} 34건이다. 앞의 둘이 사라지자 남은 하나가 드러났다.
	 *
	 * <p>원장은 DB 담당이 159건을 모두 정리했다. 이 정제는 그래도 남긴다 — 옛 형식이 어디선가
	 * 다시 들어와도 화면에는 새지 않게 하는 것이 이 함수의 목적이고, 정리된 문장에는 아무 일도
	 * 일어나지 않는다.
	 */
	private static final Pattern LEADING_TAG =
			Pattern.compile("^\\s*\\[(?:SEVERE|WARN|WARNING|INFO|CRITICAL|RISK)\\]\\s*");

	private RiskSummaryText() {
	}

	/**
	 * @param summary 판정 근거 문구. {@code null}이면 그대로 {@code null}
	 * @return 태그를 걷어낸 문장. 태그가 없으면 원문 그대로
	 */
	public static String stripSeverityTag(String summary) {
		if (summary == null) {
			return null;
		}
		return LEADING_TAG.matcher(summary).replaceFirst("").trim();
	}
}
