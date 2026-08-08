package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.ManagedReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.ManagedReportQueryRepository.ManagedReportRow;
import com.bigproject.backend.domain.reporting.presentation.dto.ManagedReportListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 담당 반 리포트 목록 조립. 여기서도 판정이 아니라 <b>번역</b>만 한다 —
 * 담당 여부는 SQL의 조인이 이미 걸렀고 공개 상태는 {@code report} 행에 있는 값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagedReportServiceImpl implements ManagedReportService {

	private final ManagedReportQueryRepository queryRepository;

	@Override
	@Transactional(readOnly = true)
	public ManagedReportListResponse findManagedReports(
			UUID managerUserId, UUID orgId, UUID cohortId, UUID roundId, UUID classId) {

		List<ManagedReportRow> rows =
				queryRepository.findManagedReports(managerUserId, orgId, cohortId, roundId, classId);

		/*
		 * 빈 목록은 예외가 아니다. 담당 반이 없는 매니저, 아직 회차가 안 끝난 기수,
		 * 필터가 너무 좁은 경우가 전부 같은 결과로 나오는데 그 셋을 서버가 구분해 줄 방법이 없다.
		 * 404를 내면 화면은 "권한이 없다"로 오독하게 된다.
		 */
		if (rows.isEmpty()) {
			log.info("담당 반 리포트 없음: managerUserId={}, cohortId={}, roundId={}, classId={}",
					managerUserId, cohortId, roundId, classId);
		}

		return ManagedReportListResponse.from(rows);
	}
}
