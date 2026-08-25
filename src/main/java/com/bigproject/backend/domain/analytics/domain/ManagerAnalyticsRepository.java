package com.bigproject.backend.domain.analytics.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ManagerAnalyticsRepository {
	/** 지금 계층의 비교 단위별 격자다. 행 식별자는 계층에 따라 반·팀·교육생이다. */
	List<HeatmapCell> findHeatmap(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			String level, String attemptView, UUID classroomId, UUID teamId);

	/**
	 * 화면 상단 합계 행이다. 반별 평균을 다시 평균 내면 인원 가중이 깨지므로
	 * <b>개인 단위에서 한 번에</b> 집계한다.
	 */
	List<HeatmapCell> findSummary(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId);

	/** 합계 행의 명부 인원이다. 담당 반 전체 · 그 반 · 그 팀 순으로 좁혀진다. */
	int countMembers(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId);

	/**
	 * <b>이 회차에 수행은 있는데 문제 결과가 하나도 없는 인원</b>이다(34차 R2·R3).
	 *
	 * <h2>왜 따로 세는가</h2>
	 *
	 * <p>히트맵 뷰의 grain은 {@code problem_stage}다. 세션을 아예 열지 못한 수행은
	 * <b>{@code problem_no}가 {@code NULL}인 행 하나</b>로만 남는데, 격자 질의가 전부
	 * {@code problem_no IS NOT NULL}로 그 행을 버렸다. 그래서 그 사람은 {@code rows}에서도
	 * 사라지고 {@code summary}의 세 카운터에도 안 잡혔다 — 화면에는 「전체 5명」이라 쓰고
	 * 4행만 그리면서 <b>차이를 설명할 자리가 어디에도 없는</b> 상태가 됐다.
	 *
	 * <p>실제로 두 가지가 여기 있다.
	 *
	 * <pre>
	 * 미응시      status=EXPIRED · terminal=NOT_ATTENDED     세션이 없다
	 * 분석 실패    status=FAILED  · terminal=ANALYSIS_FAILED  팀 전체가 통째로 빠진다
	 * </pre>
	 *
	 * <p>뒤쪽이 34차 R3다 — 한 팀의 팀원 전원이 이 상태면 <b>팀 행 자체가 없어져</b> 매니저가
	 * 그 팀으로 들어갈 방법이 사라진다.
	 *
	 * <h2>{@code rowId}가 {@code null}일 수 있다</h2>
	 *
	 * <p>이름 조인을 {@code LEFT}로 둔다. 팀 배정이 회차 시점 창에 걸리지 않으면 뷰의
	 * {@code team_id}가 {@code NULL}로 오는데, 그 인원도 <b>합계에서는 세야</b> 하기 때문이다.
	 * 행으로 그릴 수 없을 뿐이라, 합계는 전부 더하고 행은 이름이 있는 것만 만든다.
	 *
	 * @param level {@code CLASS}·{@code TEAM}·{@code TRAINEE}. 묶는 단위를 정한다
	 */
	List<UnresolvedGroup> findUnresolved(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			String level, UUID classroomId, UUID teamId);

	List<ConceptAxis> findConcepts(UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId);

	/** 반·문항별 집단 미달 판정이다. 유효 응시자가 없으면 값이 {@code null}이다. */
	List<GroupShortfall> findGroupShortfall(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId);

	List<ClassParticipant> findParticipatingClassrooms(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId);

	List<TeamParticipant> findParticipatingTeams(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId, UUID classroomId);

	List<RiskSignal> findRiskSignals(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID classroomId,
			UUID traineeId, String reasonCode);

	ConceptScope findConceptScope(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID classroomId, UUID teachesId);

	/**
	 * 격자 셀 하나의 원본이다.
	 *
	 * <p>가로축은 <b>개념({@code teachesId})</b>이다. 문제 순번({@code problem_no})이 아니다 —
	 * 순번은 {@code uq_assessment_problem_code_analysis_id_problem_no}가 말하듯 <b>팀 분석마다</b>
	 * 다시 1부터 매겨지므로, 한 회차 안에서도 팀에 따라 1번이 가리키는 개념이 다르다.
	 */
	record HeatmapCell(
			UUID rowId, String rowName, UUID teachesId, BigDecimal value, String status,
			Integer validCount, Integer notAttendedCount, Integer invalidCount, Integer interruptedCount,
			Integer initialLevel, Integer comparisonLevel, Integer delta, OffsetDateTime asOfAt) {
	}

	/**
	 * 결과가 하나도 없는 인원을 계층 단위로 묶은 것이다(34차 R2·R3).
	 *
	 * <p>{@code rowId}·{@code rowName}은 이름을 못 찾으면 {@code null}이다 —
	 * {@link #findUnresolved} 설명 참고.
	 */
	record UnresolvedGroup(
			UUID rowId, String rowName,
			int notAttendedCount, int invalidCount, int interruptedCount, int pendingCount) {

		/** 이 묶음의 인원. 격자에 자리가 없던 사람들의 수다. */
		public int total() {
			return notAttendedCount + invalidCount + interruptedCount + pendingCount;
		}

		/**
		 * 개인 행에 그릴 상태 하나.
		 *
		 * <p>개인은 넷 중 하나만 1이라 사실상 그 값이 그대로 나온다. 한 사람에게 INITIAL 수행이
		 * 둘 이상 있는 드문 경우에만 우선순위가 쓰이며, <b>사람이 먼저 봐야 하는 순</b>으로 둔다 —
		 * 무효 확정이 가장 무겁고, 판정 전({@code PENDING})이 가장 가볍다.
		 */
		public String dominantStatus() {
			if (invalidCount > 0) {
				return "INVALID";
			}
			if (interruptedCount > 0) {
				return "INTERRUPTED";
			}
			if (notAttendedCount > 0) {
				return "NOT_ATTENDED";
			}
			return "PENDING";
		}
	}

	/**
	 * 가로축 한 칸이다. 열 순번은 싣지 않는다 — 목록의 순서가 곧 순번이고, 화면에 나가는
	 * 번호는 서비스가 이 순서대로 다시 매긴다.
	 */
	record ConceptAxis(UUID teachesId, String conceptName) {
	}

	record GroupShortfall(UUID classroomId, UUID teachesId, Boolean shortfall) {
	}

	record ClassParticipant(UUID classroomId, String className, int memberCount) {
	}

	record TeamParticipant(UUID teamId, String teamName, int memberCount) {
	}

	record RiskSignal(
			UUID signalId, String reasonCode, UUID assessmentRoundId, UUID classroomId,
			UUID teamId, UUID traineeId, String traineeName, String summary,
			String status, int policyVersion, OffsetDateTime detectedAt) {
	}

	record ConceptScope(
			UUID teachesId, String conceptName, long lowLevelCount, long validRespondentCount,
			BigDecimal lowLevelRate, String scope, int policyVersion, OffsetDateTime calculatedAt) {
	}
}
