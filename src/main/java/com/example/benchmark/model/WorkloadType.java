package com.example.benchmark.model;

public enum WorkloadType {
    INSERT_ONLY("Insert Only",       "連続インサート"),
    UPDATE_HEAVY("Update Heavy",     "既存キーを繰り返し更新"),
    POINT_READ("Point Read",         "主キーで1件検索"),
    RANGE_READ("Range Read",         "価格範囲スキャン"),
    DELETE_HEAVY("Delete Heavy",     "大量行削除"),
    MIXED("Mixed Workload",          "Read 70% / Write 30%"),
    STORAGE_SIZE("Storage Size",     "データ領域サイズ計測");

    public final String displayName;
    public final String description;

    WorkloadType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
