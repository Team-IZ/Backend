package projectexecution.application;

import projectexecution.domain.Checkpoint;
import projectexecution.domain.MeasurementPlan;
import projectexecution.infrastructure.CheckpointQuestionFocusRepository;
import projectexecution.infrastructure.CheckpointRepository;
import projectexecution.infrastructure.MeasurementPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckpointService {

    // 화면 "검증 개념 3건 고정"에서 온 상수 — 회차 하나는 항상 이 개수의 검증 개념을 가져야 한다
    private static final long REQUIRED_QUESTION_FOCUS_COUNT = 3;

    private final CheckpointRepository checkpointRepository;
    private final MeasurementPlanRepository measurementPlanRepository;
    private final CheckpointQuestionFocusRepository checkpointQuestionFocusRepository;
    private final ProjectService projectService;

    @Transactional
    public Checkpoint createCheckpoint(UUID projectId, UUID orgId, UUID cohortId,
                                       LocalDate measurementDate, Integer questionCount, boolean isFinal, UUID actorUserId) {

        // measurement_plan이 없으면 이 프로젝트의 첫 checkpoint이므로 계획부터 만든다
        MeasurementPlan plan = measurementPlanRepository.findByProjectIdAndOrgId(projectId, orgId)
                .orElseGet(() -> measurementPlanRepository.save(
                        MeasurementPlan.builder()
                                .orgId(orgId)
                                .cohortId(cohortId)
                                .projectId(projectId)
                                .createdBy(actorUserId)
                                .build()));

        // project_sequence_no: 이 프로젝트 안에서 몇 번째인지
        List<Checkpoint> existing = checkpointRepository.findByPlanIdOrderByProjectSequenceNoAsc(plan.getPlanId());
        int nextProjectSequenceNo = existing.size() + 1;

        // track_sequence_no: 기수 전체를 통틀어 몇 번째인지. 동시에 두 회차가 만들어지면
        // 번호가 겹칠 수 있으니, 실제 서비스에선 이 조회~저장 사이에 락이 필요하다 (TODO)
        Integer maxTrackSequenceNo = checkpointRepository.findMaxTrackSequenceNoByCohortId(cohortId);
        int nextTrackSequenceNo = (maxTrackSequenceNo == null ? 0 : maxTrackSequenceNo) + 1;

        Checkpoint checkpoint = Checkpoint.builder()
                .planId(plan.getPlanId())
                .projectSequenceNo(nextProjectSequenceNo)
                .trackSequenceNo(nextTrackSequenceNo)
                .measurementDate(measurementDate)
                .questionCount(questionCount)
                .isFinal(isFinal)
                .createdBy(actorUserId)
                .build();

        return checkpointRepository.save(checkpoint);
    }

    public Checkpoint findCheckpoint(UUID checkpointId) {
        return checkpointRepository.findByCheckpointId(checkpointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."));
    }

    // "검증 개념 3건을 먼저 정하세요" 화면(#draft)의 근거.
    // 이걸 통과 못 하면 일정·현황 탭을 열 수 없다는 규칙을 여기서 강제한다
    public void assertReadyToOpen(UUID checkpointId) {
        long count = checkpointQuestionFocusRepository.countByCheckpointIdAndEffectiveToIsNull(checkpointId);
        if (count != REQUIRED_QUESTION_FOCUS_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "검증 개념 3건을 먼저 정해야 합니다. 현재 " + count + "건 선택됨.");
        }
    }

    // 응시 창을 연다. 검증 개념 3건이 안 채워졌으면 막고, 첫 회차 오픈이면 프로젝트도 RUNNING으로 전환
    @Transactional
    public void openCheckpoint(UUID checkpointId, UUID projectId, UUID orgId, UUID actorUserId) {
        assertReadyToOpen(checkpointId);

        Checkpoint checkpoint = findCheckpoint(checkpointId);
        checkpoint.open();

        // 첫 checkpoint가 열리는 순간에만 프로젝트를 RUNNING으로.
        // 이미 RUNNING이면 start()가 예외를 던지므로 여기서 무시한다 (두 번째 이후 회차 오픈은 정상 케이스)
        try {
            projectService.markRunning(projectId, orgId, actorUserId);
        } catch (IllegalStateException ignored) {
            // 이미 RUNNING이면 여기로 온다
        }
    }

    @Transactional
    public void closeCheckpoint(UUID checkpointId) {
        Checkpoint checkpoint = findCheckpoint(checkpointId);
        checkpoint.close();
    }

    @Transactional
    public void completeCheckpoint(UUID checkpointId) {
        Checkpoint checkpoint = findCheckpoint(checkpointId);
        checkpoint.complete();
    }
}