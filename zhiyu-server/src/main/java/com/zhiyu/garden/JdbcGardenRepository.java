package com.zhiyu.garden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!memory")
public class JdbcGardenRepository implements GardenRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcGardenRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    private String encode(GardenState state) {
        try { return mapper.writeValueAsString(state); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法保存花园", error); }
    }
    private GardenState decode(String value) {
        try { return mapper.readValue(value, GardenState.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法读取花园", error); }
    }
    public void create(String hash, GardenState state) {
        jdbc.update("INSERT INTO garden_spaces(token_hash, state_json) VALUES (?, ?)", hash, encode(state));
    }
    public GardenState read(String hash) {
        return jdbc.query("SELECT state_json FROM garden_spaces WHERE token_hash = ?", (rs, row) -> decode(rs.getString(1)), hash).stream().findFirst().orElse(null);
    }
    @Transactional
    public GardenState update(String hash, Consumer<GardenState> change) {
        GardenState state = jdbc.query("SELECT state_json FROM garden_spaces WHERE token_hash = ? FOR UPDATE", (rs, row) -> decode(rs.getString(1)), hash).stream().findFirst().orElse(null);
        if (state == null) return null;
        change.accept(state);
        jdbc.update("UPDATE garden_spaces SET state_json = ? WHERE token_hash = ?", encode(state), hash);
        return state;
    }
    public void delete(String hash) { jdbc.update("DELETE FROM garden_spaces WHERE token_hash = ?", hash); }
}
