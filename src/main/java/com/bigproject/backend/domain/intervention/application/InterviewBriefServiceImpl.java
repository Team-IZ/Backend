package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.domain.InterventionErrorCode;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefRepository.BriefHeader;
import com.bigproject.backend.domain.intervention.domain.InterviewCaseLookupRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewCaseLookupRepository.CaseSummary;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewBriefServiceImpl implements InterviewBriefService {

	private final InterviewCaseLookupRepository caseLookupRepository;
	private final InterviewBriefRepository briefRepository;

	@Override
	@Transactional(readOnly = true)
	public BriefView findBrief(UUID managerUserId, UUID orgId, UUID caseId) {
		// 화면은 caseId(candidate_id)만 들고 있는데 브리프 뷰는 interview_id 기준이다.
		// 목록 뷰에서 한 번 짚어 담당 스코프까지 같이 확인한다.
		CaseSummary summary = caseLookupRepository.findCase(managerUserId, orgId, caseId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_CASE_NOT_FOUND));

		if (summary.interviewId() == null) {
			// 아직 브리프를 만들지 않았다. 생성은 POST(IV-04)가 한다 — GET이 만들지 않는다.
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}

		BriefHeader header = briefRepository.findHeader(managerUserId, orgId, summary.interviewId())
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED));

		if (header.briefId() == null) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}

		List<BriefItemView> items = briefRepository.findItems(header.briefId()).stream()
				.map(item -> new BriefItemView(
						item.briefItemId(), item.questionText(), item.questionRationale(), item.suggestedOrder()))
				.toList();

		return new BriefView(
				caseId,
				summary.traineeUserId(),
				summary.traineeName(),
				summary.className(),
				summary.riskType(),
				summary.riskSummary(),
				"INVALID_ATTEMPT".equals(header.briefType()),
				header.firstInterview(),
				briefState(header),
				header.openingRemark(),
				items,
				briefRepository.findPriorInterview(summary.traineeUserId(), summary.interviewId())
						.map(prior -> new PriorInterviewView(prior.completedAt(), prior.nextAction()))
						.orElse(null),
				savedRecord(summary.interviewId()),
				// ⚠️ 아래 둘은 DB 회신 대기분이다(제안서 B-2·B-3). 화면이 해당 블록을 그리지
				// 않도록 빈 값으로 둔다 — 지어낸 값을 넣으면 매니저가 그것을 근거로 면담한다.
				List.of(),
				null);
	}

	/**
	 * 매니저 입력 복원. 원인은 {@code interview_cause}, 서술은 {@code interview_activity}에서 온다.
	 *
	 * <p>둘 다 비어 있으면 아직 저장한 적이 없는 브리프라 null을 준다 — 화면이 빈 입력으로 시작한다.
	 */
	private SavedRecordView savedRecord(UUID interviewId) {
		List<String> causes = briefRepository.findCauseCodes(interviewId);
		var record = briefRepository.findLatestRecord(interviewId);

		if (causes.isEmpty() && record.isEmpty()) {
			return null;
		}
		return new SavedRecordView(
				causes,
				record.map(InterviewBriefRepository.ManagerRecord::why).orElse(null),
				record.map(InterviewBriefRepository.ManagerRecord::nextAction).orElse(null));
	}

	/**
	 * 목록과 같은 4종으로 접는다. 뷰의 {@code brief_persistence_status}는 <b>생성 실패를
	 * 구분하지 않아</b>(내용 없는 DRAFT도 {@code DRAFT_SAVED}다) 여는 말 유무를 함께 본다.
	 */
	private static String briefState(BriefHeader header) {
		if (header.briefId() == null) {
			return "NONE";
		}
		if (header.openingRemark() == null) {
			return "FAILED";
		}
		return "CONFIRMED".equals(header.briefStatus()) ? "CONFIRMED" : "DRAFT";
	}
}
