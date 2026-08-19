package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository.FinalizeContext;
import com.bigproject.backend.domain.reporting.infrastructure.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 발행 예정 시각 때문에 보류된 리포트를 시각이 지난 뒤 발행한다.
 *
 * <h2>왜 별도 배치가 필요한가</h2>
 *
 * <p>리포트 생성이 <b>문제 단위 즉시</b>로 바뀌면서(2026-08-11), 마지막 문제가 끝나는 시점이
 * 운영자가 정한 발행 예정 시각({@code report_publish_not_before_at})보다 이를 수 있게 됐다.
 *
 * <p>종전에는 대상 조회가 <b>그 시각까지 아예 만들지 않는 것</b>으로 규칙을 지켰다. 이제 만들기는
 * 하되 {@link ReportRunFinalizer}가 발행만 보류하므로, 시각이 지난 뒤 발행할 누군가가 필요하다.
 * 그게 이 클래스다.
 *
 * <p>보류된 리포트는 스냅샷과 근거가 이미 다 들어가 있다 — 여기서 하는 일은
 * {@code published_at}을 찍는 것뿐이고, 그 순간 학생이 본다.
 *
 * <h2>유효성을 다시 본다</h2>
 *
 * <p>보류된 사이에 세션이 무효로 바뀔 수 있다. 확정 시점에 정상이었어도 여기서 다시 확인한다 —
 * 무효인 리포트를 시각이 지났다고 내보내면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPublishService {

	private final ReportRepository reportRepository;
	private final ReportDispatchRepository dispatchRepository;

	/**
	 * 발행 시각이 지난 보류분을 발행한다.
	 *
	 * <p>리포트 하나마다 예외를 삼킨다 — 한 건이 실패해도 나머지는 나가야 하고, 실패한 것은
	 * {@code published_at}이 비어 있는 채로 남아 다음 주기에 다시 집힌다.
	 *
	 * @return 발행한 건수
	 */
	public int publishDueReports() {
		List<UUID> reportIds = dispatchRepository.findReportsAwaitingPublish();
		if (reportIds.isEmpty()) {
			return 0;
		}

		int published = 0;
		for (UUID reportId : reportIds) {
			try {
				if (publishOne(reportId, Instant.now())) {
					published++;
				}
			} catch (RuntimeException exception) {
				log.error("보류 리포트 발행 실패: reportId={}", reportId, exception);
			}
		}
		if (published > 0) {
			log.info("보류된 리포트를 발행했다: {}건", published);
		}
		return published;
	}

	/**
	 * 리포트 하나를 발행한다.
	 *
	 * <p>🔴 <b>유효성을 다시 확인한다.</b> 확정 시점에 정상이었어도 보류된 사이에 세션이 무효로
	 * 바뀔 수 있다. 세션을 못 찾으면 발행하지 않는다 — 근거 없이 내보내는 것보다 남겨 두는 편이 낫다.
	 */
	@Transactional
	public boolean publishOne(UUID reportId, Instant now) {
		Report report = reportRepository.findById(reportId).orElse(null);
		if (report == null || report.getPublishedAt() != null) {
			// 다른 인스턴스가 먼저 발행했거나 행이 사라졌다. 둘 다 오류가 아니다.
			return false;
		}

		UUID sessionId = dispatchRepository.findSessionIdByReport(reportId).orElse(null);
		if (sessionId == null) {
			log.warn("리포트를 만든 세션을 찾지 못해 발행하지 않는다: reportId={}", reportId);
			return false;
		}

		FinalizeContext context = dispatchRepository.findFinalizeContext(sessionId).orElse(null);
		if (context == null || !context.getEligible()) {
			log.warn("세션이 정상 완료되지 않아 발행하지 않는다: reportId={}, sessionId={}", reportId, sessionId);
			return false;
		}

		// 발행이 곧 공개다. 종전에는 여기서 설정을 보고 자동 공개까지 갈지 정했는데,
		// 공개/비공개가 없어지면서 그 분기가 사라졌다(2026-08-19).
		report.publish(now);
		reportRepository.save(report);

		log.info("보류 리포트 발행: reportId={}, publishAfter={}", reportId, context.getPublishNotBeforeAt());
		return true;
	}
}
