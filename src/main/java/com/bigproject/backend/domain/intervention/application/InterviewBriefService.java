package com.bigproject.backend.domain.intervention.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MG-04 면담 브리프.
 *
 * <p>브리프는 <b>브리프 열기 클릭 시 온디맨드로 1회 생성</b>되고 그 뒤로는 DB에서 읽는다
 * (정의서 §5 "그때 브리프를 만든다 — 미리 만들어 두지 않는다"). 이 인터페이스의 조회는
 * 생성 이후를 담당하고, 생성 자체는 {@code createBrief}(IV-04)가 갖는다.
 */
public interface InterviewBriefService {

	/**
	 * 저장된 브리프를 읽는다. AI를 부르지 않는다.
	 *
	 * @throws com.bigproject.backend.global.exception.ApiException 담당 밖이거나 브리프가 없으면 404
	 */
	BriefView findBrief(UUID managerUserId, UUID orgId, UUID caseId);

	/**
	 * 브리프를 만든다. <b>AI를 호출하므로 수 초~수십 초 걸린다.</b>
	 *
	 * <p>이미 완성된 브리프가 있으면 재생성하지 않고 그대로 돌려준다 — 재생성은
	 * 별도 경로(IV-07)가 갖는다. 매니저가 열 때마다 여는 말이 달라지면 안 되고,
	 * LLM 비용도 열람 횟수만큼 나가서는 안 된다.
	 *
	 * @throws com.bigproject.backend.global.exception.ApiException
	 *         담당 밖이면 404, 무효 확인이 안 끝났으면 409, AI 생성 실패면 503
	 */
	BriefView createBrief(UUID managerUserId, UUID orgId, UUID caseId, String traceId);

	/**
	 * @param openingRemark ★ AI가 생성한 여는 말. 화면 ①칸에 그대로 표시한다
	 * @param items         ★ AI가 생성한 질문 체크리스트. <b>고르는 UI가 없어 전부 그린다</b>
	 * @param isVoid        무효 응시 브리프인가. true면 여는 말·질문이 통째로 다르다(정의서 §6-2)
	 * @param savedRecord   재오픈 시 복원할 매니저 입력. 처음 여는 브리프는 null
	 * @param concepts      막힌 개념. ⚠️ 교안 위치·반 문제 판정은 DB 회신 대기라 아직 비어 있다
	 * @param voidEvidence  "시스템이 본 것". ⚠️ 질문 복사 판정 규칙이 미확정이라 아직 null
	 */
	record BriefView(
			UUID caseId,
			UUID traineeId,
			String name,
			String className,
			String riskType,
			String riskSummary,
			boolean isVoid,
			boolean firstInterview,
			String briefState,
			String openingRemark,
			List<BriefItemView> items,
			PriorInterviewView priorInterview,
			SavedRecordView savedRecord,
			List<ConceptView> concepts,
			VoidEvidenceView voidEvidence) {
	}

	record BriefItemView(
			UUID itemId,
			String questionText,
			String questionRationale,
			Integer suggestedOrder) {
	}

	/** 지난 면담에서 정한 것. 화면 ①칸 아래 ⚠ 줄에 인용된다. */
	record PriorInterviewView(Instant completedAt, String nextAction) {
	}

	/**
	 * @param causes 원인 분류 7종 중 고른 것. {@code interview_cause}에서 복원한다
	 * @param why    상세 사유(매니저가 타이핑)
	 */
	record SavedRecordView(List<String> causes, String why, String nextAction) {
	}

	/** ⚠️ 미구현 — 교안 위치·반 문제 판정이 DB 회신 대기(제안서 B-3). */
	record ConceptView(String name, String curriculumRef, String groupIssueClassLabel) {
	}

	/** ⚠️ 미구현 — "질문 문장 그대로 복사" 판정 규칙이 DB 회신 대기(제안서 B-2). */
	record VoidEvidenceView(int unanswered, int totalQuestions, boolean copied, int durationMin) {
	}
}
