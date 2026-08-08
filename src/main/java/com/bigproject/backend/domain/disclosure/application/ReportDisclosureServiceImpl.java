package com.bigproject.backend.domain.disclosure.application;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.disclosure.infrastructure.ReportDisclosureRepository;
import com.bigproject.backend.domain.disclosure.presentation.dto.ReportDisclosureResponse;
import com.bigproject.backend.domain.disclosure.presentation.dto.UpdateReportDisclosureRequest;
import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 공개 상태 전이의 <b>권한과 순서</b>만 담당한다. 상태 조합을 맞추는 일은
 * {@code Report#release}·{@code Report#withhold}가 하므로 여기서 컬럼을 직접 건드리지 않는다.
 *
 * <p>에러 코드는 Reporting의 {@code ReportErrorCode}를 그대로 쓴다. 프론트가 보는 것은
 * <b>리포트 하나의 상태</b>이고 도메인 경계는 서버 사정이라, 같은 리소스에 두 벌의 코드 체계를
 * 두면 화면이 어느 쪽을 분기해야 하는지 알 수 없다.
 * {@code REPORT_DISCLOSURE_NOT_CONFIGURED}·{@code REPORT_WITHHELD}·
 * {@code REPORT_DISCLOSURE_SCOPE_INVALID}가 이미 그 시트에 정의돼 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDisclosureServiceImpl implements ReportDisclosureService {

	private final ReportDisclosureRepository reportDisclosureRepository;

	@Override
	@Transactional(readOnly = true)
	public ReportDisclosureResponse findForTrainee(UUID traineeUserId, UUID reportId) {
		Report report = reportDisclosureRepository.findByReportIdAndUserId(reportId, traineeUserId)
				.orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));

		return ReportDisclosureResponse.from(report);
	}

	@Override
	@Transactional
	public ReportDisclosureResponse update(UUID managerUserId, UUID managerOrgId, UUID reportId,
			UpdateReportDisclosureRequest request) {

		Report report = reportDisclosureRepository.findByReportId(reportId)
				.orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));

		// 존재 확인과 담당 확인의 결과를 같은 404로 합친다 — 둘을 나누면 응답 차이로
		// "그 reportId는 있다"가 드러난다.
		if (!reportDisclosureRepository.isManagedBy(reportId, managerUserId, managerOrgId)) {
			log.info("담당하지 않는 리포트의 공개 범위 변경 시도: reportId={}, managerUserId={}",
					reportId, managerUserId);
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}

		DisclosureScope scope = request.scope();
		if (scope == DisclosureScope.PRIVATE) {
			report.withhold();
		} else {
			// release가 scope=null·PRIVATE을 막는다. @NotNull이 이미 null을 걸렀으므로
			// 여기 남는 것은 SUMMARY·FULL뿐이지만, 방어는 도메인에 두고 중복하지 않는다.
			report.release(scope, managerUserId, Instant.now());
		}

		Report saved = reportDisclosureRepository.save(report);
		log.info("리포트 공개 범위 변경: reportId={}, scope={}, status={}, managerUserId={}",
				reportId, saved.getTraineeDisclosureScope(), saved.getTraineeReleaseStatus(), managerUserId);

		return ReportDisclosureResponse.from(saved);
	}
}
