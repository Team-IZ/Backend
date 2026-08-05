package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.AiModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** AI 모델 마스터. SA-03 모델·단가 탭에서 목록 조회와 단가 수정에 사용한다. */
public interface AiModelRepository extends JpaRepository<AiModel, UUID> {

	/** 단가 표에 쓸 모델 목록. 사용 이력이 있는 모델은 물리 삭제하지 않으므로 INACTIVE도 함께 보여준다. */
	List<AiModel> findAllByOrderByProviderAscModelCodeAsc();

	List<AiModel> findByModelIdIn(Collection<UUID> modelIds);

	/**
	 * 모델 코드로 조회한다. v07에서 ai_usage가 model_id FK 대신 model_code를 복사해 두므로,
	 * 사용량 화면이 코드 목록으로 표시명을 한 번에 해석할 때 쓴다.
	 */
	List<AiModel> findByModelCodeIn(Collection<String> modelCodes);
}
