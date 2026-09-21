package com.transformersas.marketplace.reports;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V9 debe convertir los reportes y decisiones que ya existían con el esquema V8 en casos de moderación. */
@Testcontainers
class ModerationMigrationTests {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("migration_test").withUsername("test").withPassword("test");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    @Test
    void reportsAndDecisionsFromTheOldSchemaAreGroupedIntoCases() {
        flyway("8").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword()));

        // Contenido 1: dos reportes abiertos (uno en investigación). Contenido 2: reporte ya resuelto con RETIRAR.
        // Contenido 3: reporte escalado (la decisión "escalar" desaparece).
        insertReport(jdbc, 1, "user_a", "PUBLICACION", "10", "PENDIENTE", null);
        insertReport(jdbc, 2, "user_b", "PUBLICACION", "10", "EN_INVESTIGACION", "agent_1");
        insertReport(jdbc, 3, "user_c", "RESENA", "20", "RESUELTO", "agent_1");
        insertReport(jdbc, 4, "user_d", "MENSAJE", "30", "ESCALADO", "agent_2");
        insertAction(jdbc, 3, "RETIRAR", "EN_INVESTIGACION", "RESUELTO");
        insertAction(jdbc, 4, "ESCALAR_A_ADMINISTRADOR", "EN_INVESTIGACION", "ESCALADO");

        flyway("9").migrate();

        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT content_type, content_id, status, report_count, open_key, assigned_agent_id "
                        + "FROM moderation_cases ORDER BY content_type, content_id");
        assertThat(cases).hasSize(3);
        assertThat(cases.get(0)).containsEntry("content_type", "MENSAJE").containsEntry("status", "EN_REVISION")
                .containsEntry("open_key", 1);
        assertThat(cases.get(1)).containsEntry("content_type", "PUBLICACION").containsEntry("report_count", 2)
                .containsEntry("status", "EN_REVISION").containsEntry("assigned_agent_id", "agent_1");
        assertThat(cases.get(2)).containsEntry("content_type", "RESENA").containsEntry("status", "RESUELTO")
                .containsEntry("open_key", null);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE case_id IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT case_id) FROM reports WHERE content_id='10'",
                Long.class)).isEqualTo(1);

        List<Map<String, Object>> actions = jdbc.queryForList(
                "SELECT decision, previous_status, new_status, measure_result FROM moderation_actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).containsEntry("decision", "RETIRAR")
                .containsEntry("previous_status", "EN_REVISION").containsEntry("new_status", "RESUELTO");
    }

    private void insertReport(JdbcTemplate jdbc, long id, String reporter, String type, String contentId,
                              String status, String agent) {
        jdbc.update("INSERT INTO reports (id, reporter_id, content_type, content_id, reason, description, status, "
                + "assigned_agent_id, created_at, updated_at, resolved_at) VALUES (?,?,?,?,?,?,?,?,NOW(6),NOW(6),"
                + "CASE WHEN ?='RESUELTO' THEN NOW(6) END)",
                id, reporter, type, contentId, "SPAM", "desc", status, agent, status);
    }

    private void insertAction(JdbcTemplate jdbc, long reportId, String decision, String previous, String next) {
        jdbc.update("INSERT INTO moderation_actions (report_id, agent_id, decision, justification, previous_status, "
                + "new_status, created_at) VALUES (?,?,?,?,?,?,NOW(6))",
                reportId, "agent_1", decision, "justificación", previous, next);
    }
}
