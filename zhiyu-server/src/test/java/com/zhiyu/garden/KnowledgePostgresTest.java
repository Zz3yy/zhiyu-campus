package com.zhiyu.garden;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Run only against a disposable, empty test database, never the application database. */
@EnabledIfEnvironmentVariable(named="ZHIYU_PGVECTOR_TEST_URL", matches=".+")
class KnowledgePostgresTest {
    @Test void flywayCosineSearchAndRevocationWorkOnPostgres() {
        var dataSource = new DriverManagerDataSource(System.getenv("ZHIYU_PGVECTOR_TEST_URL"), "postgres", "");
        var flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        var jdbc = new JdbcTemplate(dataSource);
        var docs = KnowledgeBase.corpus();
        var reviews = docs.stream().map(d -> new KnowledgeBase.Review(d.id(), d.contentHash(), "合成审核员", "2026-10-01")).toList();
        var kb = new KnowledgeBase(jdbc, docs, reviews);
        var result = kb.search("只打开一份复习资料", Set.of("exam_open"));
        assertEquals("pgvector", result.mode());
        assertEquals("MATCHED", result.status());
        assertTrue(result.citations().stream().anyMatch(c -> c.activityId().equals("exam_open")));
        assertEquals(21, jdbc.queryForObject("SELECT count(*) FROM garden_knowledge_documents", Integer.class));
        assertEquals(256, jdbc.queryForObject("SELECT vector_dims(embedding) FROM garden_knowledge_documents LIMIT 1", Integer.class));
        jdbc.update("UPDATE garden_knowledge_documents SET content_hash='different-version' WHERE id='activity-exam_open'");
        assertTrue(kb.search("只打开一份复习资料", Set.of("exam_open")).citations().stream().noneMatch(c -> c.activityId().equals("exam_open")));
        var revoked = new KnowledgeBase(jdbc, docs, List.of());
        assertEquals("NO_APPROVED_CONTENT", revoked.search("复习", Set.of("exam_open")).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM garden_knowledge_documents WHERE review_status='APPROVED'", Integer.class));
    }
}
