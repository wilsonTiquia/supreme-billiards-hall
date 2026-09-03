package com.supremebilliardshall.billiards_hall_system.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

// branch_setting is keyed by (branch_id, key) — the only composite primary key in the schema.
public class BranchSettingId implements Serializable {

    private UUID branchId;
    private String key;

    public BranchSettingId() {
    }

    public BranchSettingId(UUID branchId, String key) {
        this.branchId = branchId;
        this.key = key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BranchSettingId that)) {
            return false;
        }
        return Objects.equals(branchId, that.branchId) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(branchId, key);
    }
}
