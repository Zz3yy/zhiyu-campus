package com.zhiyu.garden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("memory")
public class MemoryGardenRepository implements GardenRepository {
    private final Map<String, GardenState> spaces = new HashMap<>();
    private final ObjectMapper mapper;
    public MemoryGardenRepository(ObjectMapper mapper) { this.mapper = mapper; }
    private GardenState copy(GardenState state) { return mapper.convertValue(state, GardenState.class); }
    public synchronized void create(String hash, GardenState state) { spaces.put(hash, copy(state)); }
    public synchronized GardenState read(String hash) { return spaces.containsKey(hash) ? copy(spaces.get(hash)) : null; }
    public synchronized GardenState update(String hash, Consumer<GardenState> change) {
        GardenState state = read(hash);
        if (state == null) return null;
        change.accept(state);
        spaces.put(hash, copy(state));
        return state;
    }
    public synchronized void delete(String hash) { spaces.remove(hash); }
}
