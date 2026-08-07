package com.bigproject.backend.domain.disclosure.application;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.disclosure.infrastructure.ReportDisclosureRepository;
import com.bigproject.backend.domain.disclosure.presentation.dto.ReportDisclosureResponse;
import com.bigproject.backend.domain.disclosure.presentation.dto.UpdateReportDisclosureRequest;
import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.domain.reporting.domain.TraineeReleaseStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDisclosureServiceImplTest {

	private final ReportDisclosureRepository repository = mock(ReportDisclosureRepository.class);
	private final ReportDisclosureService service = new ReportDisclosureServiceImpl(repository);

	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID COHORT_ID = UUID.randomUUID();
	private static final UUID TRAINEE_ID = UUID.randomUUID();
	private static final UUID ROUND_ID = UUID.randomUUID();
	private static final UUID MANAGER_ID = UUID.randomUUID();
	private static final UUID REPORT_ID = UUID.randomUUID();

	private static Report traineeReport() {
		return Report.forTrainee(ORG_ID, COHORT_ID, TRAINEE_ID, ROUND_ID);
	}

	private void managedByRequestingManager(boolean managed) {
		when(repository.isManagedBy(REPORT_ID, MANAGER_ID, ORG_ID)).thenReturn(managed);
	}

	private void savePassesThrough() {
		when(repository.save(any(Report.class))).thenAnswer(call -> call.getArgument(0));
	}

	// ── 교육생 조회 ──────────────────────────────────────────────

	@Test
	void 공개_범위_미지정은_오류가_아니라_상태로_돌려준다() {
		when(repository.findByReportIdAndUserId(REPORT_ID, TRAINEE_ID))
				.thenReturn(Optional.of(traineeReport()));

		ReportDisclosureResponse response = service.findForTrainee(TRAINEE_ID, REPORT_ID);

		// TR-04가 `공개 범위 미지정`을 그려야 하므로 예외를 던지면 안 된다.
		assertThat(response.releaseStatus()).isEqualTo(TraineeReleaseStatus.NOT_CONFIGURED);
		assertThat(response.scope()).isNull();
		assertThat(response.bodyVisible()).isFalse();
		assertThat(response.visibleFields().said()).isFalse();
		assertThat(response.visibleFields().qa()).isFalse();
	}

	@Test
	void 남의_리포트는_403이_아니라_404다() {
		// 소유자 조건이 붙은 조회라 남의 리포트면 애초에 빈 값이 온다.
		when(repository.findByReportIdAndUserId(REPORT_ID, TRAINEE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findForTrainee(TRAINEE_ID, REPORT_ID))
				.isInstanceOf(ReportException.class)
				.extracting(e -> ((ReportException) e).errorCode())
				.isEqualTo(ReportErrorCode.REPORT_NOT_FOUND);
	}

	// ── 매니저 설정 ──────────────────────────────────────────────

	@Test
	void SUMMARY로_공개하면_시각과_주체가_함께_채워진다() {
		Report report = traineeReport();
		when(repository.findByReportId(REPORT_ID)).thenReturn(Optional.of(report));
		managedByRequestingManager(true);
		savePassesThrough();

		ReportDisclosureResponse response = service.update(
				MANAGER_ID, ORG_ID, REPORT_ID, new UpdateReportDisclosureRequest(DisclosureScope.SUMMARY));

		// CHECK ck_report_trainee_release_status_2 — RELEASED면 셋이 모두 있어야 한다.
		assertThat(response.releaseStatus()).isEqualTo(TraineeReleaseStatus.RELEASED);
		assertThat(response.scope()).isEqualTo(DisclosureScope.SUMMARY);
		assertThat(response.releasedAt()).isNotNull();
		assertThat(report.getTraineeReleasedBy()).isEqualTo(MANAGER_ID);

		// SUMMARY는 서술·교안 위치까지만 열고 문답 원문은 닫는다.
		assertThat(response.visibleFields().said()).isTrue();
		assertThat(response.visibleFields().curriculumRef()).isTrue();
		assertThat(response.visibleFields().qa()).isFalse();
	}

	@Test
	void FULL이어야_문답_원문이_열린다() {
		Report report = traineeReport();
		when(repository.findByReportId(REPORT_ID)).thenReturn(Optional.of(report));
		managedByRequestingManager(true);
		savePassesThrough();

		ReportDisclosureResponse response = service.update(
				MANAGER_ID, ORG_ID, REPORT_ID, new UpdateReportDisclosureRequest(DisclosureScope.FULL));

		assertThat(response.visibleFields().qa()).isTrue();
	}

	@Test
	void PRIVATE은_공개가_아니라_비공개_확정이다() {
		Report report = traineeReport();
		when(repository.findByReportId(REPORT_ID)).thenReturn(Optional.of(report));
		managedByRequestingManager(true);
		savePassesThrough();

		ReportDisclosureResponse response = service.update(
				MANAGER_ID, ORG_ID, REPORT_ID, new UpdateReportDisclosureRequest(DisclosureScope.PRIVATE));

		// CHECK — WITHHELD면 시각·주체가 반드시 비어 있어야 한다.
		assertThat(response.releaseStatus()).isEqualTo(TraineeReleaseStatus.WITHHELD);
		assertThat(response.scope()).isEqualTo(DisclosureScope.PRIVATE);
		assertThat(response.releasedAt()).isNull();
		assertThat(report.getTraineeReleasedBy()).isNull();
		assertThat(response.bodyVisible()).isFalse();
	}

	@Test
	void 담당하지_않는_교육생이면_저장하지_않고_404다() {
		when(repository.findByReportId(REPORT_ID)).thenReturn(Optional.of(traineeReport()));
		managedByRequestingManager(false);

		assertThatThrownBy(() -> service.update(
				MANAGER_ID, ORG_ID, REPORT_ID, new UpdateReportDisclosureRequest(DisclosureScope.FULL)))
				.isInstanceOf(ReportException.class)
				.extracting(e -> ((ReportException) e).errorCode())
				// 담당 아님을 403으로 구분하면 그 reportId가 존재한다는 사실이 샌다.
				.isEqualTo(ReportErrorCode.REPORT_NOT_FOUND);

		verify(repository, never()).save(any(Report.class));
	}

	@Test
	void 발행_전에도_공개_범위를_정할_수_있지만_본문은_아직_안_열린다() {
		Report report = traineeReport(); // DRAFT · publishedAt=null
		when(repository.findByReportId(REPORT_ID)).thenReturn(Optional.of(report));
		managedByRequestingManager(true);
		savePassesThrough();

		ReportDisclosureResponse response = service.update(
				MANAGER_ID, ORG_ID, REPORT_ID, new UpdateReportDisclosureRequest(DisclosureScope.FULL));

		// 발행과 공개는 다른 사건이라 범위는 정해지되 bodyVisible은 아직 거짓이다.
		assertThat(response.releaseStatus()).isEqualTo(TraineeReleaseStatus.RELEASED);
		assertThat(response.publishedAt()).isNull();
		assertThat(response.bodyVisible()).isFalse();
	}
}
