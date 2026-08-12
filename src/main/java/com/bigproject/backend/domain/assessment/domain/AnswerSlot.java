package com.bigproject.backend.domain.assessment.domain;

/**
 * {@code problem_stage} 한 행 안의 답변 슬롯. <b>힌트를 몇 개 쓰고 답했는지가 슬롯을 정한다</b>
 * (AI {@code TranscriptTurn.hintsUsed}: 0=질문 · 1=firstHint · 2=secondHint).
 *
 * <p>옛 {@code stage_answer_attempt} 테이블이 사라지면서 답변 시도가 별도 행이 아니라 같은 행의
 * 슬롯이 됐다. 컬럼 이름이 슬롯마다 접두어만 다르므로 여기서 접두어를 한 번만 정의하고 SQL은
 * 문자열 조립으로 만든다 — 슬롯마다 UPDATE 문을 세 벌 쓰면 하나만 고치는 사고가 난다.
 */
public enum AnswerSlot {

	QUESTION("question"),
	FIRST_HINT("first_hint"),
	SECOND_HINT("second_hint");

	private final String columnPrefix;

	AnswerSlot(String columnPrefix) {
		this.columnPrefix = columnPrefix;
	}

	/** {@code question_answer_text} · {@code first_hint_score} 처럼 컬럼 이름을 만드는 접두어. */
	public String columnPrefix() {
		return columnPrefix;
	}

	/** 이 슬롯에 답할 때의 {@code hintsUsed} 값. AI 계약과 같은 규칙이다. */
	public int hintsUsed() {
		return ordinal();
	}

	/** @throws IllegalArgumentException 0~2 밖의 값 — AI 스키마가 최대 2로 막고 있어 정상 경로에서는 오지 않는다 */
	public static AnswerSlot ofHintsUsed(int hintsUsed) {
		if (hintsUsed < 0 || hintsUsed >= values().length) {
			throw new IllegalArgumentException("hintsUsed는 0~2여야 한다: " + hintsUsed);
		}
		return values()[hintsUsed];
	}
}
