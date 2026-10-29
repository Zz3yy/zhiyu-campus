package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Public, versioned content only. User queries and vectors are never persisted. */
@Service
public class KnowledgeBase {
    static final String MODEL = "local-char-bigram-v1";
    static final int DIMENSIONS = 256;
    public record Document(String id, String activityId, String title, String content, String source,
                           String boundary, String version, String contentHash) {}
    public record Review(String id, String contentHash, String reviewer, String reviewedAt) {}
    public record Citation(String id, String activityId, String title, String source, String content,
                           String boundary, String version, String reviewStatus, String reviewer, String reviewedAt) {}
    public record Retrieval(String mode, String status, List<Citation> citations) {}
    private final JdbcTemplate jdbc;
    private final List<Document> documents;
    private final Map<String, Review> approved;
    private volatile boolean indexed;

    @Autowired
    public KnowledgeBase(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper mapper) throws java.io.IOException {
        this(jdbc.getIfAvailable(), corpus(), loadReviews(mapper));
    }

    KnowledgeBase(JdbcTemplate jdbc, List<Document> documents, List<Review> reviews) {
        this.jdbc = jdbc;
        this.documents = List.copyOf(documents);
        Map<String, Review> valid = new HashMap<>();
        for (Review review : reviews) {
            if (review.reviewer() == null || review.reviewer().isBlank()) continue;
            try { if (LocalDate.parse(review.reviewedAt()).isAfter(LocalDate.now())) continue; }
            catch (RuntimeException ignored) { continue; }
            documents.stream().filter(d -> d.id().equals(review.id()) && d.contentHash().equals(review.contentHash()))
                .findFirst().ifPresent(d -> valid.put(d.id(), review));
        }
        approved = Map.copyOf(valid);
    }

    private static List<Review> loadReviews(ObjectMapper mapper) throws java.io.IOException {
        try (var stream = KnowledgeBase.class.getResourceAsStream("/knowledge/reviews.json")) {
            if (stream == null) throw new java.io.IOException("Missing knowledge review manifest");
            return List.of(mapper.readValue(stream, Review[].class));
        }
    }

    static List<Document> corpus() {
        List<Document> result = new ArrayList<>();
        int number = 0;
        for (var activity : ActivityCatalog.ALL) {
            result.add(document("activity-" + activity.id(), activity.id(), "校园微行动目录·第 " + (++number) + " 条：" + activity.title(),
                activity.method() + "。" + activity.instructions(), activity.source(),
                "自助草稿；可拒绝或停止，不作诊断或疗效承诺。引用链接是参考资料，不代表机构审核或逐字摘录。"));
        }
        result.add(document("guide-fact-prediction", "", "认知引导·区分事实与预测",
            "担心考试失败时，可以先区分已经发生的事实与对未来的预测；不要求立刻变得积极，也不否定真实困难。",
            "https://www.nhs.uk/mental-health/self-help/guides-tools-and-activities/thought-record/",
            "项目改写草稿，待人工核对原文；不是认知治疗或诊断。用户不愿继续时停止。"));
        result.add(document("guide-small-start", "", "复习引导·允许小步开始",
            "面对资料太多、拖延或担心学不会，可以讨论用户愿意尝试的最小起点，接受拒绝和休息，不把跳过解释为懒惰。",
            "项目产品约定：产品构思与开发约定-v1.md",
            "自拟交互原则，无临床有效性证据；不得自行增加任务。"));
        return List.copyOf(result);
    }

    static Document document(String id, String activityId, String title, String content, String source, String boundary) {
        String version = "1";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                String.join("\n", id, activityId, title, content, source, boundary, version).getBytes(StandardCharsets.UTF_8)));
            return new Document(id, activityId, title, content, source, boundary, version, hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public List<Citation> catalog() { return documents.stream().map(this::citation).toList(); }
    public List<Document> reviewQueue() { return documents; }
    public Citation activitySource(String id) {
        return documents.stream().filter(d -> d.activityId().equals(id)).findFirst().map(this::citation).orElse(null);
    }
    private Citation citation(Document d) {
        Review review = approved.get(d.id());
        return new Citation(d.id(), d.activityId(), d.title(), d.source(), d.content(), d.boundary(), d.version(),
            review == null ? "PENDING" : "APPROVED", review == null ? "" : review.reviewer(), review == null ? "" : review.reviewedAt());
    }

    public Retrieval search(String query, Set<String> allowedActivityIds) {
        String mode = jdbc == null ? "memory-vector" : "pgvector";
        try {
            if (jdbc != null) ensureIndexed();
            List<Document> eligible = documents.stream().filter(d -> approved.containsKey(d.id()))
                .filter(d -> d.activityId().isEmpty() || allowedActivityIds.contains(d.activityId())).toList();
            if (eligible.isEmpty()) return new Retrieval(mode, "NO_APPROVED_CONTENT", List.of());
            double[] vector = embed(query == null ? "" : query.substring(0, Math.min(query.length(), 1000)));
            List<Document> ranked;
            if (jdbc == null) {
                ranked = eligible.stream().filter(d -> similarity(vector, embed(d.title() + d.content())) >= 0.12)
                    .sorted(Comparator.comparingDouble((Document d) -> similarity(vector, embed(d.title() + d.content()))).reversed()
                        .thenComparing(Document::id)).limit(3).toList();
            } else {
                String placeholders = String.join(" OR ", Collections.nCopies(eligible.size(), "(id = ? AND content_hash = ?)"));
                List<Object> params = new ArrayList<>();
                params.add(vectorLiteral(vector));
                eligible.forEach(d -> { params.add(d.id()); params.add(d.contentHash()); });
                params.add(MODEL);
                var ids = jdbc.query("SELECT id FROM (SELECT id, 1 - (embedding <=> CAST(? AS vector)) AS score "
                    + "FROM garden_knowledge_documents WHERE (" + placeholders + ") AND review_status = 'APPROVED' AND embedding_model = ?) ranked "
                    + "WHERE score >= 0.12 ORDER BY score DESC, id LIMIT 3", (rs, row) -> rs.getString("id"), params.toArray());
                ranked = ids.stream().map(id -> eligible.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow()).toList();
            }
            return new Retrieval(mode, ranked.isEmpty() ? "NO_MATCH" : "MATCHED", ranked.stream().map(this::citation).toList());
        } catch (org.springframework.dao.DataAccessException e) {
            // Never log the query, connection credentials, or exception payload.
            return new Retrieval(mode, "UNAVAILABLE", List.of());
        }
    }

    private synchronized void ensureIndexed() {
        if (indexed) return;
        for (Document d : documents) {
            Citation c = citation(d);
            jdbc.update("""
                INSERT INTO garden_knowledge_documents (id, content_hash, title, content, source, boundary, version,
                    review_status, reviewer, reviewed_at, embedding_model, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, '')::date, ?, CAST(? AS vector))
                ON CONFLICT (id) DO UPDATE SET content_hash=EXCLUDED.content_hash, title=EXCLUDED.title,
                    content=EXCLUDED.content, source=EXCLUDED.source, boundary=EXCLUDED.boundary, version=EXCLUDED.version,
                    review_status=EXCLUDED.review_status, reviewer=EXCLUDED.reviewer, reviewed_at=EXCLUDED.reviewed_at,
                    embedding_model=EXCLUDED.embedding_model, embedding=EXCLUDED.embedding
                """, d.id(), d.contentHash(), d.title(), d.content(), d.source(), d.boundary(), d.version(),
                c.reviewStatus(), c.reviewer(), c.reviewedAt(), MODEL, vectorLiteral(embed(d.title() + d.content())));
        }
        indexed = true;
    }

    /** Deterministic lexical hashing, not a neural/semantic embedding model. */
    static double[] embed(String text) {
        String clean = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        double[] vector = new double[DIMENSIONS];
        for (int i = 0; i < clean.length(); i++) {
            String token = clean.substring(i, Math.min(clean.length(), i + 2));
            vector[Math.floorMod(token.hashCode(), DIMENSIONS)]++;
        }
        double norm = Math.sqrt(Arrays.stream(vector).map(v -> v * v).sum());
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }
    static double similarity(double[] a, double[] b) {
        double result = 0; for (int i = 0; i < a.length; i++) result += a[i] * b[i]; return result;
    }
    static String vectorLiteral(double[] vector) { return Arrays.toString(vector); }
}
