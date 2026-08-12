package com.bigproject.backend.domain.assessment.domain;

/**
 * 답변 한 번의 채점 결과. <b>통과 여부는 점수에서 도출한다</b> — 3점 미만이면 실패다.
 *
 * <h2>왜 AI가 준 {@code passed}를 그대로 쓰지 않는가</h2>
 *
 * <p>{@code problem_stage}에는 점수와 통과가 <b>따로</b> 저장되고, DB가 둘의 정합을 CHECK로 강제한다
 * ({@code ck_problem_stage_question_score_2} 외 2종 — {@code passed=TRUE}면 {@code score>=3},
 * {@code passed=FALSE}면 {@code score<3}). AI 응답의 두 필드를 그대로 옮겨 적으면 그 쌍이 어긋나는
 * 순간 UPDATE가 CHECK 위반으로 거절되고, 학생이 30분을 쓴 세션의 답변 한 건이 통째로 유실된다.
 * 임계값을 여기서 소유하면 두 값이 <b>항상</b> 같은 근거에서 나오므로 그 사고가 성립하지 않는다.
 *
 * <p>판정 기준이 백엔드에 없으면 "몇 점부터 통과인가"가 AI 프롬프트 안에만 남는다는 문제도 있다.
 * 정책이 바뀌었을 때 고칠 자리가 코드에 없다.
 */
public record AnswerGrade(int score, boolean passed) {

	/** 통과 하한. DB CHECK의 {@code >= 3}과 같은 값이며, 한쪽만 바꾸면 UPDATE가 거절된다. */
	public static final int PASS_SCORE = 3;

	/** {@code ck_problem_stage_*_score}가 허용하는 범위. */
	public static final int MIN_SCORE = 0;
	public static final int MAX_SCORE = 5;

	/**
	 * 점수로 판정을 만든다.
	 *
	 * <p>범위 밖 점수는 저장하지 않고 채점 실패로 되돌린다. 0~5를 벗어난 값은 CHECK가 어차피 거절하는데,
	 * 그때는 트랜잭션이 깨지면서 학생에게 500이 나가고 답변이 사라진다. 여기서 걸러야 학생이 같은 답을
	 * 그대로 다시 제출할 수 있다.
	 *
	 * @throws SessionException 점수가 0~5 밖일 때
	 */
	public static AnswerGrade of(int score) {
		if (score < MIN_SCORE || score > MAX_SCORE) {
			throw new SessionException(SessionErrorCode.GRADING_FAILED);
		}
		return new AnswerGrade(score, score >= PASS_SCORE);
	}
}
