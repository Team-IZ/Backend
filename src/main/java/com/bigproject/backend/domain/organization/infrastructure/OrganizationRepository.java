package com.bigproject.backend.domain.organization.infrastructure;

import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
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
	// ORDER BY를 JPQL에 두지 않는다 — 목업 SA-01 툴바에 `정렬 ▾`이 생겨 정렬을 Pageable(Sort)로 받기 때문이다.
	// JPQL에 ORDER BY가 있으면 Pageable의 Sort가 뒤에 덧붙어 의도치 않은 이중 정렬이 된다.
	@Query("""
			SELECT o FROM Organization o
			WHERE (:likePattern IS NULL OR o.normalizedName LIKE :likePattern)
			  AND (:status IS NULL OR o.status = :status)
			""")
	Page<Organization> search(
			@Param("likePattern") String likePattern,
			@Param("status") OrganizationStatus status,
			Pageable pageable
	);

	/**
	 * 기관명 중복 여부.
	 *
	 * <p>⚠ v06에서 {@code normalized_name}이 <b>부분 유니크(deleted_at IS NULL) → 전체 UNIQUE</b>로 바뀌었다.
	 * 정의서: "물리 파기 전에는 상태와 관계없이 재사용하지 않는다". 그래서 삭제 여부를 조건에서 뺐다 —
	 * soft-delete된 기관의 이름도 여전히 선점 상태이므로, 예전처럼 {@code deleted_at IS NULL}로 걸러 보면
	 * "사용 가능"이라 답한 뒤 INSERT에서 DB 유니크 위반으로 터진다(409가 아니라 500).
	 */
	boolean existsByNormalizedName(String normalizedName);

	/** 자기 자신을 제외한 이름 중복 여부. 이름 변경 시 같은 기관의 현재 이름과 충돌하지 않도록 쓴다. */
	boolean existsByNormalizedNameAndOrgIdNot(String normalizedName, UUID orgId);

	/** 생성 멱등성 키로 이미 만들어진 기관을 찾는다. 같은 키 재요청이면 새로 만들지 않고 이 결과를 돌려준다. */
	Optional<Organization> findByCreateIdempotencyKey(UUID createIdempotencyKey);

	/** 삭제 멱등성 키로 이미 처리된 삭제 요청을 찾는다. */
	Optional<Organization> findByDeletionIdempotencyKey(UUID deletionIdempotencyKey);

	/**
	 * 삭제되지 않은 전체 기관 ID. 플랫폼 전체 집계(SA-01 상단 지표 카드)에서 AI 비용·저장량을 합산할 대상 목록으로 쓴다.
	 * 엔티티 전체를 로딩할 필요가 없어 ID만 뽑는다.
	 */
	@Query("SELECT o.orgId FROM Organization o WHERE o.deletedAt IS NULL")
	List<UUID> findAliveOrganizationIds();
}
