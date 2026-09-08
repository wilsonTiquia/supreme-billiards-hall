package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Voucher;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository extends BranchScopedRepository<Voucher> {

    // The code as stored: normalised by VoucherCodes before it gets here. A code from another
    // branch is simply not found, which is the refusal the counter should see -- naming the
    // branch it belongs to would tell one hall about another's giveaway.
    @Query("""
            select v from Voucher v
            where v.branchId = :#{@branchContext.currentBranchId}
              and v.code = :code
            """)
    Optional<Voucher> findByCode(@Param("code") String code);

    @Query("""
            select v from Voucher v
            where v.branchId = :#{@branchContext.currentBranchId}
              and v.redeemedBillId = :billId
            """)
    Optional<Voucher> findByRedeemedBillId(@Param("billId") UUID billId);

    /*
     * SINGLE USE, ENFORCED BY THE DATABASE.
     *
     * The `redeemedAt is null` in the WHERE clause is the check. Two tills submitting the same
     * code at the same moment both pass every refusal above this point -- neither is expired,
     * both bills are open -- and a SELECT-then-save would have both write, with the second
     * silently overwriting the first and two customers getting a free two hours from one code.
     * Here the second UPDATE matches zero rows, and zero rows affected IS the already-redeemed
     * refusal. The same posture table_session_one_open_per_table_key states for opening a table:
     * the write is the check.
     *
     * flushAutomatically because the caller may have pending work; clearAutomatically because a
     * bulk update bypasses the persistence context and would otherwise leave a stale Voucher
     * cached. The service calls this as the FIRST write of the transaction, before it touches
     * the bill, so there is nothing dirty for the clear to detach.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Voucher v
               set v.redeemedAt = :now, v.redeemedBy = :actorId, v.redeemedBillId = :billId
             where v.id = :id
               and v.branchId = :#{@branchContext.currentBranchId}
               and v.redeemedAt is null
            """)
    int redeem(@Param("id") UUID id, @Param("billId") UUID billId,
               @Param("actorId") UUID actorId, @Param("now") OffsetDateTime now);

    // The mirror, and scoped to the bill it was redeemed against so a code cannot be released
    // by a till holding a different bill. Zero rows means it was already released -- by the
    // other tab, or by the same operator twice -- which the service reports rather than hides.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Voucher v
               set v.redeemedAt = null, v.redeemedBy = null, v.redeemedBillId = null
             where v.id = :id
               and v.branchId = :#{@branchContext.currentBranchId}
               and v.redeemedBillId = :billId
            """)
    int release(@Param("id") UUID id, @Param("billId") UUID billId);

    // The admin code list. Both filters optional, and `status` is resolved here against the
    // caller's date rather than stored, because "expired" is a fact about today and not about
    // the row -- a code expiring tomorrow is outstanding today and expired the day after with
    // nothing having written to it.
    @Query("""
            select v from Voucher v
            where v.branchId = :#{@branchContext.currentBranchId}
              and (:batchId is null or v.batchId = :batchId)
              and (:status is null
                   or (:status = 'REDEEMED'    and v.redeemedAt is not null)
                   or (:status = 'EXPIRED'     and v.redeemedAt is null and v.expiresOn < :today)
                   or (:status = 'OUTSTANDING' and v.redeemedAt is null and v.expiresOn >= :today))
            order by v.createdAt desc, v.code
            """)
    List<Voucher> search(@Param("batchId") UUID batchId, @Param("status") String status,
                         @Param("today") LocalDate today);

    @Query("""
            select v from Voucher v
            where v.branchId = :#{@branchContext.currentBranchId}
              and v.batchId = :batchId
            order by v.code
            """)
    List<Voucher> findByBatchId(@Param("batchId") UUID batchId);
}
