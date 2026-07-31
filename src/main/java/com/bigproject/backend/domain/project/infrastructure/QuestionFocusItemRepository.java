package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.QuestionFocusItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

// 참조용 마스터 테이블이라 org_id 조건이 없다 — 전체 기관이 공유하는 항목 목록
public interface QuestionFocusItemRepository extends JpaRepository<QuestionFocusItem, UUID> {

    List<QuestionFocusItem> findByActiveTrue();
}
