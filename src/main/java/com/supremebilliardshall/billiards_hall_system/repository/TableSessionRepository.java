package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.SessionStatus;
import com.supremebilliardshall.billiards_hall_system.entity.TableSession;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TableSessionRepository extends BranchScopedRepository<TableSession> {

    // The floor view: every table that is currently occupied, in one query.
    // OPEN and PAUSED are exactly the states the one-open-session-per-table index covers.
    // Bound as a parameter, not written as a literal: Hibernate renders an inline enum literal
    // as 'OPEN'::SessionStatus, naming the Java class rather than the session_status type.
    @Query("""
            select s from TableSession s
            where s.branchId = :#{@branchContext.currentBranchId}
              and s.status in :statuses
            order by s.openedAt
            """)
    List<TableSession> findByStatusIn(@Param("statuses") Collection<SessionStatus> statuses);

    @Query("""
            select s from TableSession s
            where s.branchId = :#{@branchContext.currentBranchId}
              and s.billId = :billId
            order by s.openedAt
            """)
    List<TableSession> findByBillId(@Param("billId") java.util.UUID billId);

    default List<TableSession> findAllLive() {
        return findByStatusIn(List.of(SessionStatus.OPEN, SessionStatus.PAUSED));
    }
}
