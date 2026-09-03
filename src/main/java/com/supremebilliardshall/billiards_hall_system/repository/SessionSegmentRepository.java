package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.SessionSegment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SessionSegmentRepository extends BranchScopedRepository<SessionSegment> {

    @Query("""
            select g from SessionSegment g
            where g.branchId = :#{@branchContext.currentBranchId}
              and g.sessionId = :sessionId
            order by g.seq
            """)
    List<SessionSegment> findBySessionId(@Param("sessionId") UUID sessionId);

    // Loads the segments of several live sessions at once so the floor view stays one query.
    @Query("""
            select g from SessionSegment g
            where g.branchId = :#{@branchContext.currentBranchId}
              and g.sessionId in :sessionIds
            order by g.seq
            """)
    List<SessionSegment> findBySessionIdIn(@Param("sessionIds") Collection<UUID> sessionIds);
}
