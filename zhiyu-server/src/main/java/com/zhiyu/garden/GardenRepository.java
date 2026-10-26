package com.zhiyu.garden;

import java.util.function.Consumer;

public interface GardenRepository {
    void create(String hash, GardenState state);
    GardenState read(String hash);
    GardenState update(String hash, Consumer<GardenState> change);
    void delete(String hash);
}
