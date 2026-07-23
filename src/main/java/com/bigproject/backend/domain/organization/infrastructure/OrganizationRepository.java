package com.bigproject.backend.domain.organization.infrastructure;

import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

	/**
	 * 기관 목록 조회용 동적 검색. likePattern/status가 null이면 해당 조건은 무시된다.
	 * (Controller의 findOrganizations: query, status 파라미터가 모두 선택값이기 때문)
	 *
	 * likePattern은 호출부(OrganizationServiceImpl)에서 "%foo%" 형태로 미리 만들어서 넘긴다.
	 * JPQL의 CONCAT('%', :param, '%') 함수 안에 null 파라미터를 직접 넣으면 Hibernate가 파라미터 타입을
	 * 제대로 추론하지 못해 PostgreSQL에 bytea로 바인딩되어 "character varying ~~ bytea" 오류가 발생한다.
	 */
	@Query("""
			SELECT o FROM Organization o
			WHERE (:likePattern IS NULL OR o.normalizedName LIKE :likePattern)
			  AND (:status IS NULL OR o.status = :status)
			ORDER BY o.createdAt DESC
			""")
	Page<Organization> search(
			@Param("likePattern") String likePattern,
			@Param("status") OrganizationStatus status,
			Pageable pageable
	);

	// organization 테이블의 부분 유니크 인덱스(uq_organization_normalized_name_active)와 동일한 조건으로
	// 삭제되지 않은 기관 중 이름 중복 여부를 확인한다.
	boolean existsByNormalizedNameAndDeletedAtIsNull(String normalizedName);
}
