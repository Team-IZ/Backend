package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.analytics.domain.GroupGapPolicy;
import com.bigproject.backend.domain.analytics.domain.OperationalActionQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.presentation.dto.ActionRequiredResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 오퍼레이터 대시보드 '조치 필요' 경보를 모은다.
 *
 * 네 경보는 원천도 grain도 달라 한 질의로 합치지 않고 각각 조회한 뒤 결합한다.
 * 유형별 최댓값 한 건씩만 화면에 올리므로 임계값 정책이 필요 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionRequiredAnalyticsService {

	private final AnalyticsActorGuard analyticsActorGuard;
	private final RiskTraineeQueryRepository riskTraineeQueryRepository;
	private final OperationalActionQueryRepository operationalActionQueryRepository;
	private final ClassroomRepository classroomRepository;

	public ActionRequiredResponse findActionsRequired(UUID cohortId, String actorEmail) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 조치 필요 목록을 조회할 수 있습니다.");
		RiskTraineeQueryRepository.CohortScope cohort = riskTraineeQueryRepository.findCohortScope(cohortId)
				.orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
		analyticsActorGuard.requireSameOrganization(cohort.organizationId(), actor);
		UUID organizationId = cohort.organizationId();

		ActionRequiredResponse.ManagerUnassignedAlert managerUnassigned = managerUnassigned(
				operationalActionQueryRepository.findUnassignedClasses(cohortId, organizationId));
		ActionRequiredResponse.ConceptGapAlert conceptGap = conceptGap(
				operationalActionQueryRepository.findConceptGaps(cohortId, organizationId));
		ActionRequiredResponse.GroupGapAlert groupGap = groupGap(
				operationalActionQueryRepository.findGroupGaps(cohortId, organizationId));
		ActionRequiredResponse.InterviewBacklogAlert interviewBacklog = interviewBacklog(
				operationalActionQueryRepository.findInterviewBacklogs(cohortId, organizationId));

		int actionCount = count(managerUnassigned) + count(conceptGap) + count(groupGap) + count(interviewBacklog);
		return new ActionRequiredResponse(
				cohortId,
				actionCount,
				managerUnassigned,
				conceptGap,
				groupGap,
				interviewBacklog
		);
	}

	private ActionRequiredResponse.ManagerUnassignedAlert managerUnassigned(
			List<OperationalActionQueryRepository.UnassignedClassRow> rows
	) {
		if (rows.isEmpty()) {
			return null;
		}
		return new ActionRequiredResponse.ManagerUnassignedAlert(
				rows.stream()
						.map(row -> new ActionRequiredResponse.UnassignedClass(
								row.classId(), row.className(), row.traineeCount()))
						.toList(),
				rows.stream().mapToLong(OperationalActionQueryRepository.UnassignedClassRow::traineeCount).sum()
		);
	}

	/**
	 * 질의가 이미 공백 팀 수 내림차순으로 정렬하므로 첫 행이 가장 나쁜 건이다.
	 */
	private ActionRequiredResponse.ConceptGapAlert conceptGap(
			List<OperationalActionQueryRepository.ConceptGapRow> rows
	) {
		return rows.stream().findFirst()
				.map(row -> new ActionRequiredResponse.ConceptGapAlert(
						roundRef(row.round()),
						row.teachesId(),
						row.conceptName(),
						row.gapTeamCount(),
						row.participatingTeamCount()
				))
				.orElse(null);
	}

	/**
	 * 미달 경계를 넘은 조합만 경보로 올린다. 질의는 비율 내림차순이라 첫 미달 행이 가장 나쁜 건이다.
	 */
	private ActionRequiredResponse.GroupGapAlert groupGap(
			List<OperationalActionQueryRepository.GroupGapRow> rows
	) {
		return rows.stream()
				.filter(row -> GroupGapPolicy.underperforming(row.lowLevelCount(), row.classMemberCount()))
				.findFirst()
				.map(row -> new ActionRequiredResponse.GroupGapAlert(
						roundRef(row.round()),
						row.classId(),
						row.className(),
						row.teachesId(),
						row.conceptName(),
						row.lowLevelCount(),
						row.classMemberCount()
				))
				.orElse(null);
	}

	private ActionRequiredResponse.InterviewBacklogAlert interviewBacklog(
			List<OperationalActionQueryRepository.InterviewBacklogRow> rows
	) {
		return rows.stream().findFirst()
				.map(row -> new ActionRequiredResponse.InterviewBacklogAlert(
						roundRef(row.round()),
						row.classId(),
						row.className(),
						row.maxDelayDays(),
						row.pendingInterviewCount(),
						row.notCreatedCount(),
						row.unplannedCount()
				))
				.orElse(null);
	}

	private ActionRequiredResponse.RoundRef roundRef(OperationalActionQueryRepository.RoundRef round) {
		return new ActionRequiredResponse.RoundRef(
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.projectId(),
				round.projectName()
		);
	}

	private int count(Object alert) {
		return alert == null ? 0 : 1;
	}

	// =========================================================================
	// 신규 추가: 조치 필요 항목 조회 (MG-07 담당 반 합계)
	// =========================================================================
	public ActionRequiredResponse getActionRequiredProjects(UUID classId, String actorEmail) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 조치 필요 목록을 조회할 수 있습니다.");
		// classId + orgId를 함께 물어 존재 확인과 기관 소속 확인을 한 번에 처리한다.
		// 다른 기관 소속 classId면 결과가 비어 CLASSROOM_NOT_FOUND로 떨어진다 —
		// findActionsRequired가 COHORT_NOT_FOUND·requireSameOrganization로 하던 것과 같은 목적이다.
		Classroom classroom = classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, actor.organizationId())
				.orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND));

		ActionRequiredResponse.ManagerUnassignedAlert managerUnassigned = managerUnassigned(
				operationalActionQueryRepository.findUnassignedClassesByClassId(classId));

		ActionRequiredResponse.ConceptGapAlert conceptGap = conceptGap(
				operationalActionQueryRepository.findConceptGapsByClassId(classId));

		ActionRequiredResponse.GroupGapAlert groupGap = groupGap(
				operationalActionQueryRepository.findGroupGapsByClassId(classId));

		ActionRequiredResponse.InterviewBacklogAlert interviewBacklog = interviewBacklog(
				operationalActionQueryRepository.findInterviewBacklogsByClassId(classId));

		int actionCount = count(managerUnassigned) + count(conceptGap) + count(groupGap) + count(interviewBacklog);

		// cohortId는 방금 조회한 Classroom에서 그대로 채운다 — 별도 조회 없이 얻을 수 있는 값이라
		// null로 비워둘 이유가 없다(4번 재검토).
		return new ActionRequiredResponse(
				classroom.getCohortId(),
				actionCount,
				managerUnassigned,
				conceptGap,
				groupGap,
				interviewBacklog
		);
	}
}