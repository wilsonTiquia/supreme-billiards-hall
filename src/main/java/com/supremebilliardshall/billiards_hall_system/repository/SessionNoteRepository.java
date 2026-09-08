package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.SessionNote;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SessionNoteRepository extends BranchScopedRepository<SessionNote> {

    // Oldest first: the thread reads as a conversation, and the correction has to come after
    // the note it corrects.
    @Query("""
            select n from SessionNote n
            where n.branchId = :#{@branchContext.currentBranchId}
              and n.sessionId = :sessionId
            order by n.createdAt, n.id
            """)
    List<SessionNote> findBySessionId(@Param("sessionId") UUID sessionId);

    // The thread for a whole bill: every note on every session that bill carried. Joined
    // rather than looked up per session, because a merged bill has several and the thread is
    // read as one story.
    @Query("""
            select n from SessionNote n, TableSession s
            where n.branchId = :#{@branchContext.currentBranchId}
              and s.branchId = n.branchId
              and s.id = n.sessionId
              and s.billId = :billId
            order by n.createdAt, n.id
            """)
    List<SessionNote> findByBillId(@Param("billId") UUID billId);
}
