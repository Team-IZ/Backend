package com.bigproject.backend.domain.operations.infrastructure;

import com.bigproject.backend.domain.operations.domain.AiModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** AI 모델 마스터. SA-03 모델·단가 탭에서 목록 조회와 단가 수정에 사용한다. */
public interface AiModelRepository extends JpaRepository<AiModel, UUID> {

	/** 단가 표에 쓸 모델 목록. 사용 이력이 있는 모델은 물리 삭제하지 않으므로 INACTIVE도 함께 보여준다. */
	List<AiModel> findAllByOrderByProviderAscModelCodeAsc();

	List<AiModel> findByModelIdIn(Collection<UUID> modelIds);
}
