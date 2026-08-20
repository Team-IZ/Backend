package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.application.CurriculumServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link CurriculumServiceImpl#pollPendingCurriculumAnalyses}를 주기 실행한다.
 *
 * <p>서비스에 {@code @Scheduled}를 직접 달지 않은 이유: 서비스가 자기 실행 주기까지 알면
 * "언제 도는가"를 바꾸려고 도메인 로직 파일을 열게 된다({@code ReportJobScheduler}와 같은 판단).
 * {@code CurriculumService} 인터페이스가 아니라 구현체를 직접 주입하는 이유는
 * {@code pollPendingCurriculumAnalyses}가 인터페이스 메서드가 아니기 때문이다.
 *
 * <h2>🔴 기본값을 꺼 두는 이유 (2026-08-20)</h2>
 *
 * <p>여태 이 폴링은 {@code CurriculumServiceImpl}에 {@code @Scheduled}가 직접 걸려 있어
 * <b>on/off 스위치 자체가 없었다</b> — 로컬 개발·{@code @SpringBootTest}·운영 전부에서 무조건
 * 돌았다. {@link ReportJobScheduler}를 비롯한 이 코드베이스의 다른 스케줄러는 전부
 * {@code @ConditionalOnProperty} 기본 꺼짐인데 이것만 예외였다.
 *
 * <p>켜져 있으면 앱을 띄워 둔 것만으로 대기 중인 분석에 AI 상태 조회가 나가고, 결과가 오면
 * 섹션·매핑 저장까지 실행된다 — 테스트 실행이나 로컬 기동이 실제 AI 트래픽·DB 쓰기를 일으킨다.
 * 운영에서 명시적으로 켠다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.curriculum.scheduler.enabled", havingValue = "true")
public class CurriculumJobScheduler {

    private final CurriculumServiceImpl curriculumServiceImpl;

    @Scheduled(fixedDelay = 600000)
    public void pollPendingCurriculumAnalyses() {
        try {
            curriculumServiceImpl.pollPendingCurriculumAnalyses();
        } catch (RuntimeException exception) {
            log.error("교안 분석 폴링 배치 실패", exception);
        }
    }
}
