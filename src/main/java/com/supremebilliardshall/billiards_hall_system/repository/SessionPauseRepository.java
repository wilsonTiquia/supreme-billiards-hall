package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.SessionPause;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionPauseRepository extends BranchScopedRepository<SessionPause> {

    @Query("""
            select p from SessionPause p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.sessionId = :sessionId
            order by p.pausedAt
            """)
    List<SessionPause> findBySessionId(@Param("sessionId") UUID sessionId);

    @Query("""
            select p from SessionPause p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.sessionId in :sessionIds
            order by p.pausedAt
            """)
    List<SessionPause> findBySessionIdIn(@Param("sessionIds") Collection<UUID> sessionIds);

    // The open pause, if the session is currently paused. session_pause_one_open_key
    // guarantees there is at most one.
    @Query("""
            select p from SessionPause p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.sessionId = :sessionId
              and p.resumedAt is null
            """)
    Optional<SessionPause> findOpenBySessionId(@Param("sessionId") UUID sessionId);
}
