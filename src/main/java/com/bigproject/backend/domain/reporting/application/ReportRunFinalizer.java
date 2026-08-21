package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportCompletionStatus;
import com.bigproject.backend.domain.reporting.domain.ReportEvidence;
import com.bigproject.backend.domain.reporting.domain.ReportEvidenceDecision;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItem;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItemStatus;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus;
import com.bigproject.backend.domain.reporting.domain.ReportSnapshot;
import com.bigproject.backend.domain.reporting.infrastructure.JdbcReportPayloadRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository.FinalizeContext;
import com.bigproject.backend.domain.reporting.infrastructure.ReportEvidenceRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationItemRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationRunRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportSnapshotRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 실행 1건의 item이 모두 끝났을 때 결과를 확정한다 — 스냅샷·근거·발행까지.
 *
 * <p><b>{@link ReportBatchService}와 클래스를 나눈 이유는 트랜잭션이다.</b> 확정은 네 테이블
 * ({@code report_snapshot}·{@code report_evidence}·{@code report_generation_run}·{@code report})에
 * 걸친 쓰기라 하나로 묶여야 하는데, 배치가 같은 클래스 안에서 자기 메서드를 부르면 Spring AOP가
 * 프록시를 거치지 않아 {@code @Transactional}이 <b>조용히 무시된다.</b> 별도 빈이면 프록시를 탄다.
 *
 * <p>확정 단계에는 AI 호출이 없다 — 그래서 트랜잭션 안에 둬도 HTTP 동안 DB 커넥션을 붙들지 않는다.
 */
@Slf4j
@Service
public class ReportRunFinalizer {

	/** 집계 로직 버전. 산식이 바뀌면 올려서 과거 스냅샷과 구분한다. */
	static final int CALCULATION_VERSION = 1;

	/** {@code summary_payload}의 스키마 버전. 역직렬화 분기의 근거다. */
	static final int PAYLOAD_SCHEMA_VERSION = 1;

	private final ReportRepository reportRepository;
	private final ReportGenerationRunRepository runRepository;
	private final ReportGenerationItemRepository itemRepository;
	private final ReportSnapshotRepository snapshotRepository;
	private final ReportEvidenceRepository evidenceRepository;
	private final JdbcReportPayloadRepository payloadRepository;

	/** 확정 직전 판정용. 세션 유효성·발행 예정 시각·총 문제 수를 읽는다. */
	private final ReportDispatchRepository dispatchRepository;

	private final ReportEvidenceFactory evidenceFactory;
	private final ObjectMapper objectMapper;

	public ReportRunFinalizer(
			ReportRepository reportRepository,
			ReportGenerationRunRepository runRepository,
			ReportGenerationItemRepository itemRepository,
			ReportSnapshotRepository snapshotRepository,
			ReportEvidenceRepository evidenceRepository,
			JdbcReportPayloadRepository payloadRepository,
			ReportDispatchRepository dispatchRepository,
			ReportEvidenceFactory evidenceFactory,
			ObjectMapper objectMapper) {

		this.reportRepository = reportRepository;
		this.runRepository = runRepository;
		this.itemRepository = itemRepository;
		this.snapshotRepository = snapshotRepository;
		this.evidenceRepository = evidenceRepository;
		this.payloadRepository = payloadRepository;
		this.dispatchRepository = dispatchRepository;
		this.evidenceFactory = evidenceFactory;
		this.objectMapper = objectMapper;
	}

	/**
	 * 발행해도 되는지 보고, 되면 발행한다 — <b>발행이 곧 공개다.</b>
	 *
	 * <h2>막는 것 두 가지</h2>
	 *
	 * <p><b>① 무효 세션</b> — 문제 단위 dispatch 는 세션이 끝나기 전에 요청을 보내므로, 만든 뒤에
	 * 세션이 무효로 끝날 수 있다. 스냅샷과 근거는 <b>지우지 않는다</b> — "왜 이 학생만 리포트가
	 * 없나"를 물었을 때 {@code report_generation_run}·{@code report_snapshot} 이 답이 된다
	 * (2026-08-11 ⓐ안). 발행만 하지 않는다.
	 *
	 * <p><b>② 발행 예정 시각 전</b> — 운영자가 {@code report_publish_not_before_at} 으로 "이 시각
	 * 전에는 발행하지 마라"를 정해 둔다. 종전에는 대상 조회가 그때까지 <b>만들지 않는 것</b>으로
	 * 지켰는데, 즉시 생성으로 바뀌면서 지킬 곳이 여기밖에 없다.
	 *
	 * <p>②로 보류된 리포트는 {@code ReportPublishService} 가 시각이 지난 뒤 발행한다.
	 * ①은 세션이 무효인 한 영영 발행되지 않는다 — 그게 맞다.
	 *
	 * @return 발행했으면 true
	 */
	private boolean publishIfAllowed(Report report, FinalizeContext context, UUID runId, Instant now) {
		if (!context.getEligible()) {
			log.warn("세션이 정상 완료되지 않아 발행하지 않는다. 스냅샷·근거는 남긴다: "
					+ "reportId={}, runId={}", report.getReportId(), runId);
			return false;
		}
		Instant notBefore = context.getPublishNotBeforeAt();
		if (notBefore != null && now.isBefore(notBefore)) {
			log.info("발행 예정 시각 전이라 보류한다: reportId={}, runId={}, publishAfter={}",
					report.getReportId(), runId, notBefore);
			return false;
		}

		// 종전에는 여기서 설정(app.report.auto-release.*)을 보고 공개까지 갈지 정했다. 발행과 공개가
		// 다른 사건이었기 때문인데, 공개/비공개가 폐지되면서(2026-08-19) 그 분기가 통째로 없어졌다.
		// 이제 이 한 줄이 "학생이 볼 수 있게 됐다"까지를 뜻한다.
		report.publish(now);
		return true;
	}

	/**
	 * item이 전부 종료됐으면 실행을 닫는다. 아직이면 아무것도 하지 않는다.
	 *
	 * @return 확정했으면 true
	 */
	@Transactional
	public boolean finalizeIfComplete(UUID generationRunId, Instant now) {
		ReportGenerationRun run = runRepository.findById(generationRunId).orElse(null);
		if (run == null || run.isTerminal()) {
			return false;
		}

		List<ReportGenerationItem> items = itemRepository.findByGenerationRunId(generationRunId);
		if (items.isEmpty() || !items.stream().allMatch(ReportGenerationItem::isTerminal)) {
			return false;
		}

		/*
		 * 🔴 문제가 다 모였는지 본다.
		 *
		 * 문제 단위 dispatch 는 item 을 시차를 두고 붙인다. 위의 "전부 종료" 만 보면 문제 1개가
		 * 끝난 순간에도 참이 되어 개념 카드 1장짜리 리포트가 발행된다. 나머지 두 문제는 그 뒤에
		 * 도착하는데, 그때는 run 이 이미 닫혀 있어 반영되지 않는다.
		 *
		 * 세션 맥락을 못 읽으면 확정하지 않는다 — 세션이 사라졌거나 조회가 실패한 것이라
		 * 모르는 채로 발행하는 것보다 다음 폴링에 다시 보는 편이 낫다.
		 */
		UUID sessionId = items.get(0).getSessionId();
		FinalizeContext context = dispatchRepository.findFinalizeContext(sessionId).orElse(null);
		if (context == null) {
			log.warn("세션 맥락을 읽지 못해 확정을 미룬다: runId={}, sessionId={}", generationRunId, sessionId);
			return false;
		}
		if (items.size() < context.getProblemCount()) {
			log.debug("아직 도착하지 않은 문제가 있어 확정을 미룬다: runId={}, {}/{}",
					generationRunId, items.size(), context.getProblemCount());
			return false;
		}

		Report report = reportRepository.findById(run.getReportId()).orElse(null);
		if (report == null) {
			// 리포트 행이 사라졌다면 스냅샷을 붙일 곳이 없다. 실행만 실패로 닫는다 —
			// 열어 둔 채로 두면 폴링이 매번 이 실행을 집는다.
			run.markFailed("리포트 행을 찾을 수 없습니다: reportId=" + run.getReportId(), now);
			runRepository.save(run);
			return true;
		}

		long succeeded = items.stream()
				.filter(item -> item.getStatus() == ReportGenerationItemStatus.SUCCEEDED)
				.count();

		if (succeeded == 0) {
			// 전부 실패했다. 스냅샷을 만들면 내용이 없는 리포트가 발행된다 —
			// 그건 "아직 안 나왔다"보다 나쁘다. 실행만 실패로 남기고 다음 배치가 다시 집게 둔다.
			run.markFailed(firstFailureReason(items), now);
			runRepository.save(run);
			log.warn("리포트 생성 전건 실패: runId={}, reportId={}, items={}",
					generationRunId, report.getReportId(), items.size());
			return true;
		}

		boolean full = succeeded == items.size()
				&& items.stream().noneMatch(item -> Boolean.TRUE.equals(item.getNarrativeFailed()));

		ReportSnapshot snapshot = writeSnapshot(run, report, items, full, now);
		EvidenceResult evidence = writeEvidence(snapshot, items, report);

		/*
		 * D-retry-target-count(2026-08-21): writeEvidence가 이미 계산한 decisionCode를 그대로
		 * 센다 — reachedLevel을 여기서 다시 계산하지 않는다.
		 *   WHY: 도달 단계 판정이 ReportEvidenceFactory 한 곳(decision())에만 있어야 정책이
		 *        갈리지 않는다. 두 벌이 되면(예: 여기서 별도로 problem_stage를 다시 읽어 판정)
		 *        이 프로젝트가 이미 겪은 문제(decision_code가 정책과 어긋나 얼어 있던 것, 23차
		 *        R2)가 재발한다.
		 *   COST: writeEvidence의 반환 타입이 int에서 레코드로 바뀐다 — 호출부 하나뿐이라 파급 작음.
		 *   EXIT: 산정 로직을 바꾸려면 ReportEvidenceFactory.decision()만 고치면 여기는 그대로다.
		 */
		snapshot.applyRetryTargetCount(evidence.retryTargetCount());
		snapshotRepository.save(snapshot);

		run.markCompleted(full ? ReportGenerationRunStatus.COMPLETED : ReportGenerationRunStatus.PARTIAL, now);
		runRepository.save(run);

		// 발행은 마지막이다. 스냅샷과 근거가 다 들어간 뒤에야 화면이 그릴 것이 생긴다.
		// 다만 여기서 못 하는 경우가 둘 있다 — 무효 세션과 발행 시각 전(publishNow 참고).
		boolean published = publishIfAllowed(report, context, generationRunId, now);
		reportRepository.save(report);

		// 개념 카드 수를 함께 남긴다. completion 은 AI 성공 여부만 보므로 FULL 인데 카드가 모자란
		// 경우가 있고, 그때 화면과 이 로그가 어긋난다 — 한 줄에서 바로 보이게 둔다.
		log.info("리포트 확정: reportId={}, runId={}, 문제 {}건 중 {}건 성공, 개념 카드 {}건"
						+ "(다시 볼 대상 {}건), completion={}, 발행={}",
				report.getReportId(), generationRunId, items.size(), succeeded, evidence.written(),
				evidence.retryTargetCount(), full ? "FULL" : "PARTIAL", published ? "함" : "보류");
		return true;
	}

	/**
	 * 활성 스냅샷을 교체한다.
	 *
	 * <p>이전 활성본을 먼저 내린다 — "리포트당 활성 하나"는 DB가 강제하지 않아서, 안 내리면
	 * 뷰의 {@code rs.is_active} 조인이 두 스냅샷을 물고 개념 카드가 두 배로 나온다.
	 */
	private ReportSnapshot writeSnapshot(ReportGenerationRun run, Report report,
			List<ReportGenerationItem> items, boolean full, Instant now) {

		Optional<ReportSnapshot> previous = snapshotRepository.findByReportIdAndIsActiveTrue(report.getReportId());
		previous.ifPresent(existing -> {
			existing.deactivate();
			snapshotRepository.save(existing);
		});

		int version = previous.map(ReportSnapshot::getSnapshotVersion).orElse(0) + 1;
		String payload = summaryPayload(items, now);
		int missing = (int) items.stream()
				.filter(item -> item.getStatus() != ReportGenerationItemStatus.SUCCEEDED)
				.count();

		return snapshotRepository.save(ReportSnapshot.create(
				report.getOrgId(),
				report.getReportId(),
				version,
				now,
				run.getCalculationVersion(),
				payload,
				full ? ReportCompletionStatus.FULL : ReportCompletionStatus.PARTIAL,
				items.size(),
				missing,
				ReportPayloads.sha256Hex(payload),
				run.getGenerationRunId(),
				PAYLOAD_SCHEMA_VERSION
		));
	}

	/**
	 * 문제별 AI 결과를 하나로 묶는다. item의 {@code response_payload}는 임시 버퍼이고
	 * <b>확정본은 여기</b>다(report_generation_item 테이블 코멘트).
	 *
	 * <p>결과를 파싱하지 않고 트리째 옮긴다 — DTO로 좁혔다가 다시 직렬화하면 AI가 나중에 추가한
	 * 필드가 소리 없이 사라진다.
	 */
	private String summaryPayload(List<ReportGenerationItem> items, Instant now) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("generatedAt", now.toString());

		ArrayNode problems = root.putArray("problems");
		for (ReportGenerationItem item : items) {
			ObjectNode entry = problems.addObject();
			entry.put("problemId", item.getProblemId().toString());
			entry.put("problemNo", item.getProblemNo() == null ? null : item.getProblemNo().intValue());
			entry.put("status", item.getStatus().name());
			entry.put("narrativeFailed", Boolean.TRUE.equals(item.getNarrativeFailed()));
			entry.put("failureReason", item.getFailureReason());
			entry.set("result", readResult(item));
		}
		return ReportPayloads.toJson(objectMapper, root);
	}

	/**
	 * 문제마다 개념 카드 1장을 남긴다. <b>이 행이 없으면 화면이 아무것도 못 그린다</b>
	 * ({@link ReportEvidence} javadoc).
	 *
	 * <p>실패한 item도 카드를 만든다 — 세션 기록만으로 도달 단계와 답변 발췌는 사실이고,
	 * 빠뜨리면 그 문제가 리포트에서 통째로 사라진다.
	 *
	 * <h2>🔴 건너뛴 카드는 어디에도 남지 않는다</h2>
	 *
	 * <p>stage를 못 찾아 건너뛴 문제는 {@code run.status}에도 {@code completion_status}에도
	 * 반영되지 않는다 — 그 둘은 <b>AI 생성 성공 여부</b>만 본다. AI가 다 성공했는데 카드가 빠지면
	 * 리포트는 {@code COMPLETED}·{@code FULL}로 닫히고, 화면에서만 그 문제가 사라진다.
	 * 뷰가 {@code report_evidence}를 INNER JOIN하기 때문이다.
	 *
	 * <p>그래서 로그가 유일한 신호다. 건너뛴 것이 있으면 {@code WARN}이 아니라 {@code ERROR}로
	 * 올린다 — 이건 "봐 두면 좋은 것"이 아니라 <b>학생이 볼 리포트에 구멍이 난 것</b>이다.
	 * 한 장도 못 만들면 발행은 되는데 뷰가 0건을 내므로 더 강하게 남긴다.
	 *
	 * <h2>다시 볼 대상 수도 여기서 함께 센다</h2>
	 *
	 * <p>{@code evidenceFactory.create()}가 이미 {@code decisionCode}(도달 단계 판정)를 정해서
	 * 돌려주므로, 그 값을 그대로 센다 — {@code report_snapshot.retry_target_count}를 채우려고
	 * reachedLevel을 여기서 별도로 다시 계산하지 않는다({@code decision()}이 유일한 판정처여야
	 * 한다, {@link ReportEvidenceFactory} javadoc 참고).
	 *
	 * @return 실제로 저장한 카드 수와 그중 다시 보기 대상 수
	 */
	private EvidenceResult writeEvidence(ReportSnapshot snapshot, List<ReportGenerationItem> items, Report report) {
		int written = 0;
		int retryTarget = 0;
		for (ReportGenerationItem item : items) {
			Optional<JdbcReportPayloadRepository.ConceptContext> context =
					payloadRepository.findConceptContext(item.getSessionId(), item.getProblemId());

			if (context.isEmpty()) {
				// 원인은 대개 세션이 다룬 문제의 stage가 준비되지 않은 것이다(한계 3).
				// problemId·sessionId를 함께 남겨야 어느 문제가 사라졌는지 되짚을 수 있다.
				log.error("근거를 만들 stage가 없어 개념 카드를 건너뛴다 — 이 문제는 화면에서 사라진다: "
								+ "problemId={}, sessionId={}, reportId={}",
						item.getProblemId(), item.getSessionId(), report.getReportId());
				continue;
			}

			ReportEvidence evidence = evidenceFactory.create(
					snapshot.getSnapshotId(),
					item.getProblemId(),
					context.get(),
					readResult(item),
					report.getCohortId(),
					report.getAssessmentRoundId());
			evidenceRepository.save(evidence);
			written++;
			if (evidence.getDecisionCode() == ReportEvidenceDecision.REVIEW_REQUIRED) {
				retryTarget++;
			}
		}

		if (written == 0 && !items.isEmpty()) {
			log.error("개념 카드를 한 장도 만들지 못했다. 발행은 되지만 화면은 빈 리포트를 그린다"
							+ "(trainee_report_problem_view가 report_evidence를 INNER JOIN한다): "
							+ "reportId={}, snapshotId={}, 문제 {}건",
					report.getReportId(), snapshot.getSnapshotId(), items.size());
		}
		return new EvidenceResult(written, retryTarget);
	}

	/** {@link #writeEvidence} 결과. 저장한 카드 수와 그중 다시 보기 대상 수. */
	private record EvidenceResult(int written, int retryTargetCount) {
	}

	/** item에 담아 둔 AI {@code result}. 없거나 깨졌으면 null이다. */
	private JsonNode readResult(ReportGenerationItem item) {
		if (item.getResponsePayload() == null || item.getResponsePayload().isBlank()) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(item.getResponsePayload());
			return node.isMissingNode() || node.isNull() ? null : node;
		} catch (tools.jackson.core.JacksonException exception) {
			log.warn("item 응답을 읽지 못했다. 근거는 세션 기록만으로 만든다: itemId={}",
					item.getGenerationItemId(), exception);
			return null;
		}
	}

	/** 전건 실패일 때 실행에 남길 사유. CHECK가 NOT NULL을 요구한다. */
	private static String firstFailureReason(List<ReportGenerationItem> items) {
		return items.stream()
				.map(ReportGenerationItem::getFailureReason)
				.filter(reason -> reason != null && !reason.isBlank())
				.findFirst()
				.orElse("모든 문제의 리포트 생성이 실패했습니다.");
	}
}
