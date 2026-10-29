package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only current human-approved versions can enter retrieval context. Queries are never persisted. */
@Service
public class KnowledgeBase {
    static final String MODEL = "local-char-bigram-v1";
    static final int DIMENSIONS = 256;
    static final Set<String> EMOTIONS = Set.of("anxiety", "procrastination", "frustration", "confusion");
    static final Set<String> SCENES = Set.of("dormitory", "library", "classroom", "commute");
    static final List<String> LOADS = List.of("very_low", "low", "medium");
    public record Metadata(int timeMinutes, List<String> targetEmotions, String cognitiveLoad, List<String> scenes, List<String> keywords) {}
    public record Document(String id, String activityId, String title, String content, String source,
                           String boundary, String version, String category, Metadata metadata,
                           String sourceId, String sourceLocator, String sourceRelation, String contentHash) {}
    record Entry(String id, String activityId, String title, String content, String source,
                 String boundary, String version, String category, Metadata metadata,
                 String sourceId, String sourceLocator, String sourceRelation) {}
    public record Review(String id, String contentHash, String reviewer, String reviewedAt) {}
    public record Citation(String id, String activityId, String title, String source, String content,
                           String boundary, String version, String reviewStatus, String reviewer, String reviewedAt,
                           String category, Metadata metadata, String sourceLocator, String sourceRelation) {}
    public record Filters(Integer timeLimit, String emotion, String scene, String cognitiveLoad) {
        public Filters {
            if (timeLimit != null && (timeLimit < 1 || timeLimit > 60)) throw new IllegalArgumentException("Invalid time limit");
            if (emotion != null && !EMOTIONS.contains(emotion)) throw new IllegalArgumentException("Invalid emotion");
            if (scene != null && !SCENES.contains(scene)) throw new IllegalArgumentException("Invalid scene");
            if (cognitiveLoad != null && !LOADS.contains(cognitiveLoad)) throw new IllegalArgumentException("Invalid load");
        }
        public static Filters none() { return new Filters(null, null, null, null); }
        boolean accepts(Document d) {
            Metadata m = d.metadata();
            return (timeLimit == null || m.timeMinutes() <= timeLimit)
                && (emotion == null || m.targetEmotions().contains(emotion))
                && (scene == null || m.scenes().contains(scene))
                && (cognitiveLoad == null || LOADS.indexOf(m.cognitiveLoad()) <= LOADS.indexOf(cognitiveLoad));
        }
        /** Tool refinements may narrow, never relax an explicit user constraint. */
        Filters narrow(Filters requested) {
            if (scene != null && requested.scene != null && !scene.equals(requested.scene)) throw new IllegalArgumentException("Scene conflicts with user");
            if (emotion != null && requested.emotion != null && !emotion.equals(requested.emotion)) throw new IllegalArgumentException("Emotion conflicts with user");
            Integer time = timeLimit;
            if (time == null) time = requested.timeLimit;
            else if (requested.timeLimit != null) time = Math.min(time, requested.timeLimit);
            String load = cognitiveLoad == null ? requested.cognitiveLoad : requested.cognitiveLoad == null ? cognitiveLoad
                : LOADS.get(Math.min(LOADS.indexOf(cognitiveLoad), LOADS.indexOf(requested.cognitiveLoad)));
            return new Filters(time, emotion == null ? requested.emotion : emotion, scene == null ? requested.scene : scene, load);
        }
    }
    public record Hit(Citation citation, double vectorSimilarity, double bm25, double fusionScore) {}
    public record Retrieval(String mode, String status, List<Citation> citations, List<Hit> hits, Filters filters) {
        public Retrieval(String mode, String status, List<Citation> citations) { this(mode, status, citations, List.of(), Filters.none()); }
    }
    public record Trace(String trigger, Retrieval result) {}
    private final JdbcTemplate jdbc;
    private final List<Document> documents;
    private final Map<String, Review> approved;
    private volatile boolean indexed;

    @Autowired
    public KnowledgeBase(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper mapper) throws java.io.IOException {
        this(jdbc.getIfAvailable(), corpus(), loadReviews(mapper));
    }
    KnowledgeBase(JdbcTemplate jdbc, List<Document> documents, List<Review> reviews) {
        this.jdbc = jdbc; this.documents = List.copyOf(documents);
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
        ObjectMapper mapper = new ObjectMapper();
        try (var stream = KnowledgeBase.class.getResourceAsStream("/knowledge/corpus.json")) {
            if (stream == null) throw new IllegalStateException("Missing corpus.json");
            List<Document> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (Entry e : mapper.readValue(stream, Entry[].class)) {
                if (e.id() == null || !ids.add(e.id()) || e.content() == null || e.content().isBlank()
                        || e.source() == null || e.source().isBlank() || e.metadata() == null
                        || !Set.of("activity", "concept", "scenario", "template").contains(e.category())) throw new IllegalArgumentException("Invalid corpus entry");
                Metadata m = e.metadata();
                if (m.timeMinutes() < 1 || m.timeMinutes() > 60 || !LOADS.contains(m.cognitiveLoad())
                        || m.targetEmotions().isEmpty() || !EMOTIONS.containsAll(m.targetEmotions())
                        || m.scenes().isEmpty() || !SCENES.containsAll(m.scenes())) throw new IllegalArgumentException("Invalid metadata");
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(e)));
                result.add(new Document(e.id(), e.activityId(), e.title(), e.content(), e.source(), e.boundary(), e.version(),
                    e.category(), m, e.sourceId(), e.sourceLocator(), e.sourceRelation(), hash));
            }
            return List.copyOf(result);
        } catch (Exception error) { throw new IllegalStateException("Cannot load knowledge corpus", error); }
    }
    public List<Citation> catalog() { return documents.stream().map(this::citation).toList(); }
    public List<Document> reviewQueue() { return documents; }
    public Citation activitySource(String id) {
        return documents.stream().filter(d -> d.activityId().equals(id)).findFirst().map(this::citation).orElse(null);
    }
    private Citation citation(Document d) {
        Review review = approved.get(d.id());
        return new Citation(d.id(), d.activityId(), d.title(), d.source(), d.content(), d.boundary(), d.version(),
            review == null ? "PENDING" : "APPROVED", review == null ? "" : review.reviewer(), review == null ? "" : review.reviewedAt(),
            d.category(), d.metadata(), d.sourceLocator(), d.sourceRelation());
    }
    public Retrieval search(String query, Set<String> allowedActivityIds) { return search(query, allowedActivityIds, Filters.none()); }
    public Retrieval search(String query, Set<String> allowedActivityIds, Filters filters) {
        return search(query, allowedActivityIds, filters, false);
    }
    public Retrieval searchActions(String query, Set<String> allowedActivityIds, Filters filters) {
        return search(query, allowedActivityIds, filters, true);
    }
    private Retrieval search(String query, Set<String> allowedActivityIds, Filters filters, boolean actionsOnly) {
        String mode = jdbc == null ? "memory-hybrid" : "pgvector-hybrid";
        try {
            if (jdbc != null) ensureIndexed();
            List<Document> approvedDocs = documents.stream().filter(d -> approved.containsKey(d.id()))
                .filter(d -> !actionsOnly || !d.activityId().isEmpty())
                .filter(d -> d.activityId().isEmpty() || allowedActivityIds.contains(d.activityId())).toList();
            if (approvedDocs.isEmpty()) return empty(mode, "NO_APPROVED_CONTENT", filters);
            List<Document> eligible = approvedDocs.stream().filter(filters::accepts).toList();
            if (eligible.isEmpty()) return empty(mode, "NO_MATCH", filters);
            String bounded = query == null ? "" : query.substring(0, Math.min(query.length(), 1000));
            if (tokens(bounded).isEmpty()) return empty(mode, "NO_MATCH", filters);
            double[] vector = embed(bounded); Map<String, Double> cosine = new HashMap<>();
            if (jdbc == null) eligible.forEach(d -> cosine.put(d.id(), similarity(vector, embed(searchText(d)))));
            else {
                String placeholders = String.join(" OR ", Collections.nCopies(eligible.size(), "(id = ? AND content_hash = ?)"));
                List<Object> params = new ArrayList<>(); params.add(vectorLiteral(vector));
                eligible.forEach(d -> { params.add(d.id()); params.add(d.contentHash()); }); params.add(MODEL);
                StringBuilder sql = new StringBuilder("SELECT id, 1 - (embedding <=> CAST(? AS vector)) AS score FROM garden_knowledge_documents WHERE (")
                    .append(placeholders).append(") AND review_status = 'APPROVED' AND embedding_model = ?");
                if (filters.timeLimit() != null) { sql.append(" AND time_minutes <= ?"); params.add(filters.timeLimit()); }
                if (filters.scene() != null) { sql.append(" AND scenes @> ARRAY[?]::text[]"); params.add(filters.scene()); }
                if (filters.emotion() != null) { sql.append(" AND target_emotions @> ARRAY[?]::text[]"); params.add(filters.emotion()); }
                if (filters.cognitiveLoad() != null) { sql.append(" AND cognitive_load_rank <= ?"); params.add(LOADS.indexOf(filters.cognitiveLoad())); }
                jdbc.query(sql.toString(), rs -> { cosine.put(rs.getString("id"), rs.getDouble("score")); }, params.toArray());
                eligible = eligible.stream().filter(d -> cosine.containsKey(d.id())).toList();
            }
            Map<String, Double> lexical = bm25(bounded, eligible);
            // Hash collisions are not evidence: require real lexical overlap as well.
            List<Document> matched = eligible.stream().filter(d -> lexical.getOrDefault(d.id(), 0.0) > 0).toList();
            Map<String,Integer> bmRanks = ranks(matched, lexical), vectorRanks = ranks(matched, cosine);
            List<Hit> hits = matched.stream().map(d -> new Hit(citation(d), cosine.get(d.id()), lexical.get(d.id()),
                    1.0 / (60 + bmRanks.get(d.id())) + 1.0 / (60 + vectorRanks.get(d.id()))))
                .sorted(Comparator.comparingDouble(Hit::fusionScore).reversed().thenComparing(h -> h.citation().id())).limit(3).toList();
            return new Retrieval(mode, hits.isEmpty() ? "NO_MATCH" : "MATCHED", hits.stream().map(Hit::citation).toList(), hits, filters);
        } catch (org.springframework.dao.DataAccessException error) { return empty(mode, "UNAVAILABLE", filters); }
    }
    private static Retrieval empty(String mode, String status, Filters filters) { return new Retrieval(mode, status, List.of(), List.of(), filters); }
    private static String searchText(Document d) { return d.title() + " " + d.content() + " " + String.join(" ", d.metadata().keywords()); }
    private static Map<String,Integer> ranks(List<Document> docs, Map<String,Double> scores) {
        List<Document> sorted = docs.stream().sorted(Comparator.comparingDouble((Document d) -> scores.get(d.id())).reversed().thenComparing(Document::id)).toList();
        Map<String,Integer> result = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) result.put(sorted.get(i).id(), i + 1);
        return result;
    }
    static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        var matcher = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+").matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.matches("[a-z0-9]+") || word.length() == 1) result.add(word);
            else for (int i = 0; i < word.length() - 1; i++) result.add(word.substring(i, i + 2));
        }
        return result;
    }
    /** Okapi BM25, k1=1.2, b=.75. Statistics use the eligible small corpus. */
    static Map<String, Double> bm25(String query, List<Document> docs) {
        Map<String, List<String>> tokenized = new HashMap<>(); docs.forEach(d -> tokenized.put(d.id(), tokens(searchText(d))));
        double avg = tokenized.values().stream().mapToInt(List::size).average().orElse(1);
        Map<String,Double> scores = new HashMap<>(); docs.forEach(d -> scores.put(d.id(), 0.0));
        for (String token : new HashSet<>(tokens(query))) {
            long df = tokenized.values().stream().filter(ts -> ts.contains(token)).count();
            double idf = Math.log(1 + (docs.size() - df + .5) / (df + .5));
            for (Document d : docs) {
                List<String> ts = tokenized.get(d.id()); long tf = ts.stream().filter(token::equals).count();
                if (tf > 0) scores.merge(d.id(), idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * ts.size() / avg)), Double::sum);
            }
        }
        return scores;
    }
    private synchronized void ensureIndexed() {
        if (indexed) return;
        for (Document d : documents) {
            Citation c = citation(d); Metadata m = d.metadata();
            jdbc.update("""
                INSERT INTO garden_knowledge_documents (id, content_hash, title, content, source, boundary, version,
                    review_status, reviewer, reviewed_at, embedding_model, embedding, category, time_minutes,
                    target_emotions, scenes, cognitive_load_rank)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, '')::date, ?, CAST(? AS vector), ?, ?, ?::text[], ?::text[], ?)
                ON CONFLICT (id) DO UPDATE SET content_hash=EXCLUDED.content_hash, title=EXCLUDED.title,
                    content=EXCLUDED.content, source=EXCLUDED.source, boundary=EXCLUDED.boundary, version=EXCLUDED.version,
                    review_status=EXCLUDED.review_status, reviewer=EXCLUDED.reviewer, reviewed_at=EXCLUDED.reviewed_at,
                    embedding_model=EXCLUDED.embedding_model, embedding=EXCLUDED.embedding, category=EXCLUDED.category,
                    time_minutes=EXCLUDED.time_minutes, target_emotions=EXCLUDED.target_emotions,
                    scenes=EXCLUDED.scenes, cognitive_load_rank=EXCLUDED.cognitive_load_rank
                """, d.id(), d.contentHash(), d.title(), d.content(), d.source(), d.boundary(), d.version(),
                c.reviewStatus(), c.reviewer(), c.reviewedAt(), MODEL, vectorLiteral(embed(searchText(d))), d.category(),
                m.timeMinutes(), "{" + String.join(",", m.targetEmotions()) + "}", "{" + String.join(",", m.scenes()) + "}", LOADS.indexOf(m.cognitiveLoad()));
        }
        indexed = true;
    }
    /** Extract only explicit user text, never assistant suggestions or device information. */
    static Filters userFilters(List<GardenChatController.Message> history, String latest) {
        List<String> texts = new ArrayList<>(history.stream().filter(m -> m.role().equals("user")).map(GardenChatController.Message::text).toList()); texts.add(latest);
        Integer minutes = null; String scene = null; String load = null;
        for (String text : texts) {
            var time = Pattern.compile("(?:只有|最多|愿意|可以|花|有|就|剩|用|给自己)\\s*([0-9]{1,2}|一|二|两|三|五|十)\\s*分钟").matcher(text);
            while (time.find()) {
                String n = time.group(1); int value = switch (n) { case "一" -> 1; case "二", "两" -> 2; case "三" -> 3; case "五" -> 5; case "十" -> 10; default -> Integer.parseInt(n); };
                if (value >= 1 && value <= 60) minutes = value;
            }
            var place = Pattern.compile("(?:我(?:现在)?在|现在在|在)(宿舍|图书馆|教室)|通勤|公交|地铁").matcher(text);
            while (place.find()) scene = switch (place.group(1) == null ? "通勤" : place.group(1)) {
                case "宿舍" -> "dormitory"; case "图书馆" -> "library"; case "教室" -> "classroom"; default -> "commute";
            };
            if (text.matches("(?s).*(太难|不想看|累了|更简单|低负担|不想动脑).*")) load = "very_low";
        }
        return new Filters(minutes, null, scene, load);
    }
    /** Lexical hashing, not a neural semantic embedding model. */
    static double[] embed(String text) {
        String clean = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); double[] vector = new double[DIMENSIONS];
        for (int i = 0; i < clean.length(); i++) vector[Math.floorMod(clean.substring(i, Math.min(clean.length(), i + 2)).hashCode(), DIMENSIONS)]++;
        double norm = Math.sqrt(Arrays.stream(vector).map(v -> v * v).sum());
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }
    static double similarity(double[] a, double[] b) { double result = 0; for (int i = 0; i < a.length; i++) result += a[i] * b[i]; return result; }
    static String vectorLiteral(double[] vector) { return Arrays.toString(vector); }
}
