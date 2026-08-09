package com.bigproject.backend.domain.disclosure.application;

import com.bigproject.backend.domain.disclosure.presentation.dto.ReportDisclosureResponse;
import com.bigproject.backend.domain.disclosure.presentation.dto.UpdateReportDisclosureRequest;

import java.util.UUID;

/**
 * 리포트 공개 상태 조회·설정. Reporting이 <b>무엇을 발행했나</b>를 맡고,
 * 이 도메인이 <b>누구에게 어디까지 열려 있나</b>를 맡는다.
 */
public interface ReportDisclosureService {

	/**
	 * 교육생 본인 리포트의 공개 상태. TR-04가 본문이 잠긴 <b>이유</b>를 이걸로 판별한다.
	 *
	 * <p>상태를 그대로 돌려줄 뿐 <b>예외를 던지지 않는다</b> — 미지정·비공개는 오류가 아니라
	 * 화면이 그려야 할 정상 상태다. 본문 조회({@code GET /reports/{id}})가 막히는 것과 다르다.
	 */
	ReportDisclosureResponse findForTrainee(UUID traineeUserId, UUID reportId);

	/**
	 * 담당 매니저가 공개 범위를 정한다. 담당하지 않는 교육생의 리포트는 404다 —
	 * 403으로 구분해 주면 "그 id의 리포트가 존재한다"는 사실이 새어 나간다
	 * ({@code GET /reports/{reportId}}와 같은 규칙).
	 */
	ReportDisclosureResponse update(UUID managerUserId, UUID managerOrgId, UUID reportId,
			UpdateReportDisclosureRequest request);
}
