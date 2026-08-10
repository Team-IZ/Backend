package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportCompletionStatus;
import com.bigproject.backend.domain.reporting.domain.ReportEvidence;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItem;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItemStatus;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus;
import com.bigproject.backend.domain.reporting.domain.ReportSnapshot;
import com.bigproject.backend.domain.reporting.infrastructure.JdbcReportPayloadRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportEvidenceRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationItemRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationRunRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportSnapshotRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
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
	private final ReportEvidenceFactory evidenceFactory;
	private final ObjectMapper objectMapper;

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
		int evidenceWritten = writeEvidence(snapshot, items, report);

		run.markCompleted(full ? ReportGenerationRunStatus.COMPLETED : ReportGenerationRunStatus.PARTIAL, now);
		runRepository.save(run);

		// 발행은 마지막이다. 스냅샷과 근거가 다 들어간 뒤에야 화면이 그릴 것이 생긴다.
		report.publish(now);
		reportRepository.save(report);

		// 개념 카드 수를 함께 남긴다. completion 은 AI 성공 여부만 보므로 FULL 인데 카드가 모자란
		// 경우가 있고, 그때 화면과 이 로그가 어긋난다 — 한 줄에서 바로 보이게 둔다.
		log.info("리포트 발행: reportId={}, runId={}, 문제 {}건 중 {}건 성공, 개념 카드 {}건, completion={}",
				report.getReportId(), generationRunId, items.size(), succeeded, evidenceWritten,
				full ? "FULL" : "PARTIAL");
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
	 * @return 실제로 저장한 카드 수
	 */
	private int writeEvidence(ReportSnapshot snapshot, List<ReportGenerationItem> items, Report report) {
		int written = 0;
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

			evidenceRepository.save(evidenceFactory.create(
					snapshot.getSnapshotId(),
					item.getProblemId(),
					context.get(),
					readResult(item),
					report.getCohortId(),
					report.getAssessmentRoundId()));
			written++;
		}

		if (written == 0 && !items.isEmpty()) {
			log.error("개념 카드를 한 장도 만들지 못했다. 발행은 되지만 화면은 빈 리포트를 그린다"
							+ "(trainee_report_problem_view가 report_evidence를 INNER JOIN한다): "
							+ "reportId={}, snapshotId={}, 문제 {}건",
					report.getReportId(), snapshot.getSnapshotId(), items.size());
		}
		return written;
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
