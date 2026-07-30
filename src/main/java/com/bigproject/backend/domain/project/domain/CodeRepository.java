package com.bigproject.backend.domain.project.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// DB 테이블명은 repository이지만, Spring Data의 마커 인터페이스
// org.springframework.data.repository.Repository와 이름이 겹치는 걸 피하려고
// 엔티티 이름은 CodeRepository로 짓고 @Table로 실제 테이블명을 명시한다
@Entity
@Table(name = "repository")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeRepository {
   // ===== 1묶음: 식별자 =====

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "repository_id", nullable = false, updatable = false)
        private UUID repositoryId;

        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;

        // team_id/owner_user_id 중 정확히 하나만 값이 있다 (생성자에서 검증)
        @Column(name = "team_id", updatable = false)
        private UUID teamId;

        @Column(name = "owner_user_id", updatable = false)
        private UUID ownerUserId;

        @Column(name = "org_id", nullable = false, updatable = false)
        private UUID orgId;

        // ===== 2묶음: 업무 필드 =====

        @Column(name = "repo_url", nullable = false)
        private String repoUrl;

        @Column(name = "normalized_repo_url", nullable = false)
        private String normalizedRepoUrl;

        @Enumerated(EnumType.STRING)
        @Column(name = "access_status", nullable = false, length = 100)
        private AccessStatus accessStatus;

        // NULL 허용. 저장소 연결 직후 접근 확인 전에는 확인 일시가 없을 수 있음 (최신 스키마 반영)
        @Column(name = "checked_at")
        private OffsetDateTime checkedAt;

        @Column(name = "checked_by")
        private UUID checkedBy;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 100)
        private RepositoryStatus status;

        // ===== 3묶음: 감사 필드 =====

        @CreationTimestamp
        @Column(name = "created_at", nullable = false, updatable = false)
        private OffsetDateTime createdAt;

        @UpdateTimestamp
        @Column(name = "updated_at", nullable = false)
        private OffsetDateTime updatedAt;

        // ===== 4묶음: 생성과 행동 =====

        private CodeRepository(UUID projectId, UUID teamId, UUID ownerUserId, UUID orgId,
                               String repoUrl, String normalizedRepoUrl) {
            validateOwner(teamId, ownerUserId);
            this.projectId = projectId;
            this.teamId = teamId;
            this.ownerUserId = ownerUserId;
            this.orgId = orgId;
            this.repoUrl = repoUrl;
            this.normalizedRepoUrl = normalizedRepoUrl;
            this.accessStatus = AccessStatus.PENDING; // 연결 직후엔 아직 확인 전
            this.status = RepositoryStatus.ACTIVE;
        }

        public static CodeRepository forTeam(UUID projectId, UUID teamId, UUID orgId,
                                             String repoUrl, String normalizedRepoUrl) {
            return new CodeRepository(projectId, teamId, null, orgId, repoUrl, normalizedRepoUrl);
        }

        public static CodeRepository forIndividual(UUID projectId, UUID ownerUserId, UUID orgId,
                                                   String repoUrl, String normalizedRepoUrl) {
            return new CodeRepository(projectId, null, ownerUserId, orgId, repoUrl, normalizedRepoUrl);
        }

        private static void validateOwner(UUID teamId, UUID ownerUserId) {
            boolean hasTeam = teamId != null;
            boolean hasOwner = ownerUserId != null;
            if (hasTeam == hasOwner) {
                throw new IllegalArgumentException("team_id와 owner_user_id 중 정확히 하나만 있어야 합니다.");
            }
        }

        public void recordAccessCheck(AccessStatus accessStatus, UUID checkedBy) {
            this.accessStatus = accessStatus;
            this.checkedAt = OffsetDateTime.now();
            this.checkedBy = checkedBy;
        }

        public void remove() {
            this.status = RepositoryStatus.REMOVED;
        }
}
