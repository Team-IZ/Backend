package com.bigproject.backend.domain.curriculum.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 교안 탭(OP-06 ⑤)이 보는 <b>기관 전체 교안 목록</b>과 단건 상세 조회 포트(9차 R8).
 *
 * <p>지금까지 읽기가 둘뿐이었고 둘 다 범위가 달랐다 — {@code findLinkableCurricula}는 <b>기수 하나</b>에
 * 연결 가능한 것이고, {@code findSections}는 <b>교안 하나</b>의 내용이다. 교안 탭은 기관 전체 범위라
 * 기수마다 부르고 합쳐야 했는데, ① 기수가 늘면 요청이 그만큼 늘고 ② "연결 가능한 것"은 전체와 같지 않다.
 *
 * <p>한 행이 <b>교안(material) 하나</b>이며 값은 그 교안의 <b>최신 버전</b> 기준이다. 화면이 교안을
 * 파일 하나로 다루기 때문이다(버전은 같은 자리의 새 파일이지 별도 항목이 아니다).
 *
 * <p>집계 넷(분석 상태·섹션 수·개념 수·사용 회차 수)이 서로 다른 테이블에 있어 JPA 엔티티 하나로
 * 표현되지 않는다. 목록 한 화면을 위한 조회라 조회 전용 포트로 둔다.
 */
public interface CurriculumCatalogRepository {

	/** 필터·정렬·페이지가 적용된 한 페이지. */
	List<CurriculumCatalogRow> findPage(CurriculumCatalogCriteria criteria, int limit, long offset);

	/**
	 * 이 기관에 <b>살아 있는 그 기수</b>가 있는지(22차 R7).
	 *
	 * <p>{@code GET /cohorts/{cohortId}/curricula}는 교안이 기관 단위라 경로의 기수를 쓰지 않았고,
	 * 그래서 <b>존재하지 않는 기수를 넣어도 다른 기수와 똑같은 목록 7건이 나갔다.</b> 지금은 기관이
	 * 토큰에서 오므로 남의 기관 것이 새지는 않지만, 경로가 사실과 다른 말을 하고 있었다.
	 *
	 * <p>기수는 이 도메인의 엔티티가 아니라 존재 여부 하나만 읽는다.
	 */
	boolean cohortExists(UUID cohortId, UUID orgId);

	/** 같은 필터를 적용한 전체 건수. */
	long count(CurriculumCatalogCriteria criteria);

	/**
	 * 분석 상태별 교안 수(11차 R7). 목록과 <b>같은 모집단</b>(삭제되지 않은 이 기관의 교안)을 쓰되
	 * 검색·상태 필터는 걸지 않는다 — 상태 칩이 자기 자신을 필터링하면 언제나 자기 개수만 남는다.
	 *
	 * <p>교안 탭만 이 값이 없어서 헤더에 `12개 · 분석 완료 9 · 실패 1`을 못 그리고 있었다.
	 * 기수({@code counts})·매니저({@code statusCounts})가 이미 같은 모양을 준다.
	 *
	 * <p>한 번도 분석하지 않은 교안은 상태 자체가 없다. 그런 교안은 어느 키에도 들어가지 않으므로
	 * <b>상태별 합이 전체와 다를 수 있다</b> — 호출부가 {@code NOT_ANALYZED} 자리를 따로 만든다.
	 */
	Map<CurriculumAnalysisStatus, Long> countByAnalysisStatus(UUID orgId);

	/** 교안 하나. 목록과 <b>같은 SELECT</b>를 쓰므로 두 응답의 필드가 어긋날 수 없다. */
	Optional<CurriculumCatalogRow> findOne(UUID orgId, UUID materialId);

	/**
	 * @param query           파일명·교안 제목 부분검색(대소문자 무시). null·공백이면 전체
	 * @param statuses        최신 버전의 <b>가장 최근 분석 시도</b> 상태로 좁힌다. null·빈 목록이면 전체.
	 *                        여러 값을 주면 <b>합집합</b>이다(25차 R1) — 화면이 `분석 중` 한 라벨로 묶는
	 *                        {@code PENDING}·{@code RUNNING}을 한 번에 고를 수 있어야 한다
	 * @param notAnalyzedOnly <b>한 번도 분석하지 않은</b> 교안만 남긴다(13차 R2).
	 *                        그런 교안은 분석 상태가 없어 {@code statuses}로는 고를 수 없다 —
	 *                        그래서 상태 축이 아니라 별도 조건이다({@code statuses}와 함께 쓸 수 없다)
	 */
	record CurriculumCatalogCriteria(
			UUID orgId,
			String query,
			List<CurriculumAnalysisStatus> statuses,
			boolean notAnalyzedOnly,
			CurriculumCatalogSort sort) {
	}

	/**
	 * @param analysisStatus  가장 최근 분석 <b>시도</b>의 상태. 한 번도 분석하지 않았으면 null이다 —
	 *                        재분석을 걸어 두고 진행 중인지 실패했는지 화면이 폴링할 대상이 이 값이다
	 * @param sectionCount    가장 최근 <b>성공</b>한 분석이 만든 섹션 수. 성공 분석이 없으면 0
	 * @param conceptCount    최신 버전의 승인된(ACTIVE) 개념 매핑 수
	 * @param usedProjectCount 이 교안(모든 버전)을 연결한 <b>삭제되지 않은</b> 회차 수. 삭제·교체 판단 근거
	 * @param uploadedByName  올린 사람 이름. 계정이 지워졌으면 null
	 */
	record CurriculumCatalogRow(
			UUID materialId,
			UUID versionId,
			String title,
			String originalFileName,
			Integer versionNo,
			Integer pageCount,
			CurriculumAnalysisStatus analysisStatus,
			long sectionCount,
			long conceptCount,
			long usedProjectCount,
			Instant uploadedAt,
			String uploadedByName) {
	}
}
