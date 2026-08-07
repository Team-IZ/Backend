package com.bigproject.backend.domain.organization.application;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Sort;

/**
 * 기관 목록 정렬 옵션. 목업 SA-01 툴바의 `정렬 ▾`에 대응한다.
 *
 * <p>기수 수·교육생 수·비용은 organization 테이블이 아니라 별도 배치 집계로 채우기 때문에
 * SQL 정렬 대상이 아니다(페이지를 자른 뒤 집계하므로 전역 정렬이 성립하지 않는다).
 * 그래서 organization 행 자체가 가진 컬럼만 정렬 옵션으로 노출한다.
 */
@Schema(name = "OrganizationSort", description = "기관 목록 정렬 기준", enumAsRef = true)
public enum OrganizationSort {

	/** 최근 생성순(기본). */
	CREATED_AT_DESC(Sort.by(Sort.Direction.DESC, "createdAt")),
	CREATED_AT_ASC(Sort.by(Sort.Direction.ASC, "createdAt")),
	/** 기관명 가나다순. 정규화된 이름 기준이라 대소문자 영향이 없다. */
	NAME_ASC(Sort.by(Sort.Direction.ASC, "normalizedName")),
	NAME_DESC(Sort.by(Sort.Direction.DESC, "normalizedName"));

	private final Sort sort;

	OrganizationSort(Sort sort) {
		this.sort = sort;
	}

	public Sort toSort() {
		return sort;
	}

	public static OrganizationSort orDefault(OrganizationSort sort) {
		return sort == null ? CREATED_AT_DESC : sort;
	}
}
