package com.supremebilliardshall.billiards_hall_system.entity;

import java.util.UUID;

// Marks an entity whose table carries branch_id NOT NULL. Repositories for these
// entities extend BranchScopedRepository, which scopes every read to the caller's
// branch so a forgotten predicate cannot leak another branch's rows.
public interface BranchScoped {
    UUID getBranchId();
}
