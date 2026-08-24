package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.InactivationReasonCode;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.TraineeDetailRepository;
import com.bigproject.backend.domain.member.presentation.dto.TraineeDetailResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraineeDetailService {
	/** 응시가 끝난 회차만 헤더 판정의 후보다. 진행 중·미시작은 아직 판정할 것이 없다. */
	private static final String COMPLETED = "COMPLETED";

	private final TraineeDetailRepository repository;
	private final ManagerViewScopeGuard scopeGuard;
	private final ObjectMapper objectMapper;

	public TraineeDetailResponse findDetail(String email, UUID cohortId, UUID traineeId) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		List<TraineeDetailRepository.DetailRow> rows =
				repository.findRounds(actor.userId(), cohortId, traineeId);

		// 담당 반 밖이거나 없는 교육생은 존재를 알리지 않고 같은 404로 묶는다.
		if (rows.isEmpty()) {
			throw new ApiException(MemberErrorCode.TRAINEE_NOT_FOUND);
		}

		TraineeDetailRepository.DetailRow profile = rows.get(0);
		int[] excellentSequenceNos = profile.excellentAssessmentSequenceNos();

		// 기수에 회차가 하나도 없으면 뷰가 회차 필드만 빈 행 하나를 낸다. 그 행은 격자에 넣지 않는다 --
		// 회차가 없는 것과 회차는 있는데 응시하지 않은 것은 화면이 다르게 그린다.
		List<TraineeDetailResponse.Round> rounds = new ArrayList<>();
		for (TraineeDetailRepository.DetailRow row : rows) {
			if (row.assessmentRoundId() == null) {
				continue;
			}
			rounds.add(toRound(row, excellentSequenceNos));
		}

		TraineeDetailRepository.DetailRow headerRound = latestAttendedRound(rows);
		return new TraineeDetailResponse(
				profile.traineeId(),
				profile.name(),
				profile.email(),
				profile.cohortId(),
				profile.cohortName(),
				toAccountStatus(profile.rawAccountStatus()),
				profile.classroomId(),
				profile.className(),
				toReasonCode(profile.inactivatedReasonCode()),
				profile.inactivatedReason(),
				profile.inactivatedAt(),
				headerRound == null ? null : headerRound.primaryStatusCode(),
				// 32차 R4 — 시드에 남은 [SEVERE]·[WARN]을 걷어낸다(면담 목록·브리프와 같은 처리).
				headerRound == null ? null
						: com.bigproject.backend.domain.intervention.domain.RiskSummaryText
								.stripSeverityTag(headerRound.riskReasonSummary()),
				profile.excellentOccurrenceCount() == null ? 0 : profile.excellentOccurrenceCount(),
				excellentSequenceNos,
				List.copyOf(rounds));
	}

	/**
	 * 헤더 배지는 <b>가장 최근에 응시한 회차</b> 하나만 본다. 행은 차수 오름차순이므로 뒤에서 훑는다.
	 * 위험 유형은 응시가 끝나야 판정되므로(InterviewCandidateReason은 INITIAL 결과 확정 뒤에 쌓인다)
	 * 미응시·진행 중 회차는 후보가 아니다.
	 */
	private TraineeDetailRepository.DetailRow latestAttendedRound(
			List<TraineeDetailRepository.DetailRow> rows) {
		for (int i = rows.size() - 1; i >= 0; i--) {
			TraineeDetailRepository.DetailRow row = rows.get(i);
			if (row.assessmentRoundId() != null && COMPLETED.equals(row.roundResultStatus())
					&& row.primaryStatusCode() != null) {
				return row;
			}
		}
		return null;
	}

	private TraineeDetailResponse.Round toRound(
			TraineeDetailRepository.DetailRow row, int[] excellentSequenceNos) {
		int cohortRoundNo = row.cohortRoundNo() == null ? 0 : row.cohortRoundNo();
		return new TraineeDetailResponse.Round(
				row.assessmentRoundId(),
				cohortRoundNo,
				row.roundNo() == null ? 0 : row.roundNo(),
				row.roundName(),
				row.projectId(),
				row.projectName(),
				row.attemptId(),
				row.roundResultStatus(),
				row.primaryStatusCode(),
				row.notAttendedReasonCode(),
				row.matchedRiskTypeCodes(),
				com.bigproject.backend.domain.intervention.domain.RiskSummaryText
						.stripSeverityTag(row.riskReasonSummary()),
				row.roundTerminalAt(),
				row.expectedConceptCount() == null ? 0 : row.expectedConceptCount(),
				row.lowStageConceptCount(),
				Arrays.stream(excellentSequenceNos).anyMatch(no -> no == cohortRoundNo),
				row.teamIdAtRound(),
				toConcepts(row.conceptResultItems()));
	}

	/**
	 * 뷰가 JSONB로 만든 문항별 결과를 그대로 옮긴다. 명단(MG-05)은 이 배열을 문자열째 내려주지만
	 * 상세는 격자를 바로 그리는 화면이라 여기서 풀어 준다 — 화면마다 JSON을 다시 파싱하지 않는다.
	 */
	private List<TraineeDetailResponse.Concept> toConcepts(String rawItems) {
		if (rawItems == null || rawItems.isBlank()) {
			return List.of();
		}
		JsonNode items = objectMapper.readTree(rawItems);
		List<TraineeDetailResponse.Concept> concepts = new ArrayList<>();
		for (JsonNode item : items) {
			concepts.add(new TraineeDetailResponse.Concept(
					uuid(item, "problemId"),
					item.path("problemNo").asInt(),
					uuid(item, "conceptId"),
					text(item, "conceptName"),
					text(item, "generationStatus"),
					// null은 0단이 아니다. 문항이 없거나 한 축도 답하지 않은 경우다.
					item.path("reachLevel").isNumber() ? item.path("reachLevel").asInt() : null));
		}
		return List.copyOf(concepts);
	}

	private UUID uuid(JsonNode node, String field) {
		String value = text(node, field);
		return value == null ? null : UUID.fromString(value);
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNull() || value.isMissingNode() ? null : value.asString();
	}

	private AccountStatus toAccountStatus(String rawStatus) {
		return switch (rawStatus) {
			case "PENDING", "INVITED" -> AccountStatus.INVITED;
			case "ACTIVE" -> AccountStatus.ACTIVE;
			case "INACTIVE" -> AccountStatus.INACTIVE;
			default -> throw new IllegalStateException("알 수 없는 계정 상태 값입니다: " + rawStatus);
		};
	}

	private InactivationReasonCode toReasonCode(String rawCode) {
		return rawCode == null ? null : InactivationReasonCode.valueOf(rawCode);
	}
}
