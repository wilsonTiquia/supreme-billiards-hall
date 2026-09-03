package com.supremebilliardshall.billiards_hall_system.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * What the filters offer. Built from what is actually in this branch's history, so the owner
 * picks from a list of things that happened rather than typing a constant they would have to
 * already know — and never pastes an id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditFilterOptionsDTO {

    private List<ActionOption> actions;
    private List<ActorOption> actors;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionOption {
        private String action;
        private String label;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActorOption {
        private UUID id;
        private String name;
    }
}
