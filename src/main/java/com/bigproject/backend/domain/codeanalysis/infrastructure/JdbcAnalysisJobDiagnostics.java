package com.bigproject.backend.domain.codeanalysis.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code analysis_job.external_job_id} 손상 원인을 찾기 위한 읽기 전용 진단 쿼리.
 *
 * <p>JPA 영속성 컨텍스트나 2차 캐시를 거치지 않고 실제 PostgreSQL 행을 읽는다. {@code xmin}은
 * 행이 다른 트랜잭션에서 갱신됐는지 비교할 버전 표식일 뿐, 그 트랜잭션의 PID나 사용자를 알려 주는
 * 감사 로그는 아니다. 변경 주체는 같은 시점의 {@code pg_stat_activity}와
 * {@code pg_stat_statements}를 함께 봐야 좁힐 수 있다.
 *
 * <p>테이블·컬럼·트리거를 만들거나 바꾸지 않는다. 모든 SQL은 SELECT다.
 */
@Repository
public class JdbcAnalysisJobDiagnostics {

	private static final Logger log = LoggerFactory.getLogger(JdbcAnalysisJobDiagnostics.class);

	private final JdbcTemplate jdbc;

	public JdbcAnalysisJobDiagnostics(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 실제 DB 행과 그 행 버전을 raw JDBC로 읽는다. */
	public Optional<JobSnapshot> findSnapshot(UUID jobId) {
		return jdbc.query("""
				SELECT external_job_id,
				       status,
				       xmin::text AS row_version,
				       pg_backend_pid() AS observer_pid,
				       COALESCE(current_setting('application_name', true), '') AS observer_application,
				       COALESCE(inet_client_addr()::text, 'local') AS observer_client
				  FROM analysis_job
				 WHERE job_id = ?
				""", resultSet -> {
			if (!resultSet.next()) {
				return Optional.empty();
			}
			return Optional.of(new JobSnapshot(
					resultSet.getObject("external_job_id", UUID.class),
					resultSet.getString("status"),
					resultSet.getString("row_version"),
					resultSet.getInt("observer_pid"),
					resultSet.getString("observer_application"),
					resultSet.getString("observer_client")
			));
		}, jobId);
	}

	/**
	 * 외부 ID 손상을 발견한 바로 그 시점에 writer 후보를 남긴다.
	 *
	 * <p>진단 권한이나 {@code pg_stat_statements} 확장이 없어도 폴링 종료 처리는 계속돼야 하므로,
	 * 각 카탈로그 조회 실패는 여기서 따로 삼킨다.
	 */
	public void logExternalIdLoss(UUID jobId) {
		logSnapshot(jobId);
		logConnectionGroups();
		logRecentAnalysisJobQueries();
		logUserTriggers();
		logRewriteRules();
		logAnalysisJobStatements();
	}

	private void logSnapshot(UUID jobId) {
		try {
			log.error("🔍 external_job_id 손상 시점 raw DB 행: jobId={}, snapshot={}",
					jobId, findSnapshot(jobId).orElse(null));
		} catch (RuntimeException exception) {
			logDiagnosticFailure("raw DB 행", exception);
		}
	}

	private void logConnectionGroups() {
		try {
			List<ConnectionGroup> groups = jdbc.query("""
					SELECT COALESCE(application_name, '') AS application_name,
					       COALESCE(client_addr::text, 'local') AS client_addr,
					       state,
					       count(*) AS connection_count,
					       min(backend_start)::text AS oldest_connection,
					       max(state_change)::text AS last_state_change
					  FROM pg_stat_activity
					 WHERE datname = current_database()
					 GROUP BY application_name, client_addr, state
					 ORDER BY application_name, client_addr, state
					""", (resultSet, rowNum) -> new ConnectionGroup(
					resultSet.getString("application_name"),
					resultSet.getString("client_addr"),
					resultSet.getString("state"),
					resultSet.getInt("connection_count"),
					resultSet.getString("oldest_connection"),
					resultSet.getString("last_state_change")
			));
			log.error("🔍 external_job_id writer 후보 DB 연결 그룹: {}", groups);
		} catch (RuntimeException exception) {
			logDiagnosticFailure("DB 연결 그룹", exception);
		}
	}

	/**
	 * 손상 시점의 다른 DB 세션 전부. <b>쿼리 본문으로 미리 거르지 않는다.</b>
	 *
	 * <p>종전에는 {@code query ~* 'analysis_job'}과 5분 제한을 걸었는데, 그러면 이미 커밋하고 idle로
	 * 돌아간 writer 는 잡히지 않는다 — {@code pg_stat_activity.query}는 그 세션의 <b>마지막</b> 쿼리라
	 * 커넥션 풀 keepalive 나 {@code DISCARD ALL}로 덮이고, 풀러를 거치면 아예 빈 문자열이 된다.
	 * 빈 결과가 "다른 writer 가 없다"로 잘못 읽혀서 필터를 걷어냈다.
	 *
	 * <p>{@code usename}·{@code backend_start}를 함께 남긴다. Supavisor 같은 풀러를 거치면
	 * {@code application_name}과 {@code client_addr}이 커넥션을 잡은 클라이언트마다 바뀌어 신원 근거가
	 * 되지 못한다 — 실제로 같은 pid 가 시점에 따라 다른 이름으로 보였다.
	 */
	private void logRecentAnalysisJobQueries() {
		try {
			List<ActiveQuery> queries = jdbc.query("""
					SELECT pid,
					       COALESCE(application_name, '') AS application_name,
					       COALESCE(usename, '') AS usename,
					       COALESCE(client_addr::text, 'local') AS client_addr,
					       state,
					       backend_start::text AS backend_start,
					       state_change::text AS state_change,
					       left(regexp_replace(COALESCE(query, ''), '[[:space:]]+', ' ', 'g'), 500) AS last_query
					  FROM pg_stat_activity
					 WHERE datname = current_database()
					   AND pid <> pg_backend_pid()
					 ORDER BY state_change DESC NULLS LAST
					""", (resultSet, rowNum) -> new ActiveQuery(
					resultSet.getInt("pid"),
					resultSet.getString("application_name"),
					resultSet.getString("usename"),
					resultSet.getString("client_addr"),
					resultSet.getString("state"),
					resultSet.getString("backend_start"),
					resultSet.getString("state_change"),
					resultSet.getString("last_query")
			));
			log.error("🔍 손상 시점 다른 DB 세션 전부: {}", queries);
		} catch (RuntimeException exception) {
			logDiagnosticFailure("다른 DB 세션", exception);
		}
	}

	private void logUserTriggers() {
		try {
			List<DatabaseTrigger> triggers = jdbc.query("""
					SELECT c.relname AS attached_table,
					       t.tgname AS trigger_name,
					       t.tgenabled::text AS enabled,
					       t.tgfoid::regprocedure::text AS trigger_function,
					       pg_get_triggerdef(t.oid, true) AS trigger_definition,
					       position('external_job_id' in lower(pg_get_functiondef(p.oid))) > 0
					           AS function_mentions_external_job_id
					  FROM pg_trigger t
					  JOIN pg_class c ON c.oid = t.tgrelid
					  JOIN pg_namespace n ON n.oid = c.relnamespace
					  JOIN pg_proc p ON p.oid = t.tgfoid
					 WHERE n.nspname = 'public'
					   AND c.relname IN ('analysis_job', 'measurement_attempt')
					   AND NOT t.tgisinternal
					 ORDER BY c.relname, t.tgname
					""", (resultSet, rowNum) -> new DatabaseTrigger(
					resultSet.getString("attached_table"),
					resultSet.getString("trigger_name"),
					resultSet.getString("enabled"),
					resultSet.getString("trigger_function"),
					resultSet.getString("trigger_definition"),
					resultSet.getBoolean("function_mentions_external_job_id")
			));
			log.error("🔍 analysis_job/measurement_attempt 사용자 트리거: {}", triggers);
		} catch (RuntimeException exception) {
			logDiagnosticFailure("사용자 트리거", exception);
		}
	}

	private void logRewriteRules() {
		try {
			List<RewriteRule> rules = jdbc.query("""
					SELECT schemaname,
					       tablename,
					       rulename,
					       position('external_job_id' in lower(definition)) > 0
					           AS rule_mentions_external_job_id
					  FROM pg_rules
					 WHERE tablename IN ('analysis_job', 'measurement_attempt')
					    OR definition ILIKE '%external_job_id%'
					 ORDER BY schemaname, tablename, rulename
					""", (resultSet, rowNum) -> new RewriteRule(
					resultSet.getString("schemaname"),
					resultSet.getString("tablename"),
					resultSet.getString("rulename"),
					resultSet.getBoolean("rule_mentions_external_job_id")
			));
			log.error("🔍 external_job_id 관련 PostgreSQL rewrite rule: {}", rules);
		} catch (RuntimeException exception) {
			logDiagnosticFailure("rewrite rule", exception);
		}
	}

	/**
	 * 이 DB에서 실제로 실행된 {@code analysis_job} SQL의 <b>모양</b>과 누적 실행 횟수.
	 *
	 * <p>세션 신원(application_name·client_addr)이 풀러 때문에 못 믿을 때 <b>이것이 유일한 결정적
	 * 근거다.</b> {@code external_job_id}가 SET 절에 들어간 UPDATE가 여기 하나라도 있으면 준영속
	 * 엔티티를 merge 하는 옛 코드가 어딘가에서 돌고 있다는 뜻이고, 없으면 손상 원인은 UPDATE가 아니다.
	 * 손상이 두 번 나면 같은 {@code queryid}의 {@code calls} 증가분이 곧 범인이다.
	 *
	 * <p>{@code last_exec_time}은 쓰지 않는다 — {@code pg_stat_statements} 1.11(PostgreSQL 17)부터
	 * 생긴 컬럼이라 이 DB에는 없고, 그 컬럼 하나 때문에 진단 전체가 실행되지 못했다.
	 */
	private void logAnalysisJobStatements() {
		try {
			List<StatementStat> statements = jdbc.query("""
					SELECT calls,
					       rows,
					       queryid::text AS query_id,
					       userid::regrole::text AS executed_by,
					       left(regexp_replace(query, '[[:space:]]+', ' ', 'g'), 1000) AS normalized_query
					  FROM pg_stat_statements
					 WHERE query ~* 'analysis_job'
					 ORDER BY calls DESC
					 LIMIT 30
					""", (resultSet, rowNum) -> new StatementStat(
					resultSet.getLong("calls"),
					resultSet.getLong("rows"),
					resultSet.getString("query_id"),
					resultSet.getString("executed_by"),
					resultSet.getString("normalized_query")
			));
			log.error("🔍 pg_stat_statements의 analysis_job SQL: {}", statements);
		} catch (RuntimeException exception) {
			// 확장을 설치하지 않은 DB가 정상적으로 더 많다. 손상 처리 자체에는 영향이 없다.
			logDiagnosticFailure("pg_stat_statements", exception);
		}
	}

	private void logDiagnosticFailure(String target, RuntimeException exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		log.warn("🔍 external_job_id {} 진단을 실행하지 못했다: {}", target, cause.getMessage());
	}

	public record JobSnapshot(
			UUID externalJobId,
			String status,
			String rowVersion,
			int observerPid,
			String observerApplication,
			String observerClient) {
	}

	private record ConnectionGroup(
			String applicationName,
			String clientAddress,
			String state,
			int connectionCount,
			String oldestConnection,
			String lastStateChange) {
	}

	private record ActiveQuery(
			int pid,
			String applicationName,
			String userName,
			String clientAddress,
			String state,
			String backendStart,
			String stateChange,
			String lastQuery) {
	}

	private record DatabaseTrigger(
			String attachedTable,
			String triggerName,
			String enabled,
			String triggerFunction,
			String triggerDefinition,
			boolean functionMentionsExternalJobId) {
	}

	private record RewriteRule(
			String schemaName,
			String tableName,
			String ruleName,
			boolean ruleMentionsExternalJobId) {
	}

	private record StatementStat(
			long calls,
			long rows,
			String queryId,
			String executedBy,
			String normalizedQuery) {
	}
}
