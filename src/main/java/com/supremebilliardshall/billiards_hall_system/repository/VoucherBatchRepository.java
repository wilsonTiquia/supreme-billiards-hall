package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.VoucherBatch;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.UUID;

public interface VoucherBatchRepository extends BranchScopedRepository<VoucherBatch> {

    @Query("""
            select b from VoucherBatch b
            where b.branchId = :#{@branchContext.currentBranchId} and b.archivedAt is null
            order by b.createdAt desc
            """)
    List<VoucherBatch> findAllNewestFirst();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from VoucherBatch b where b.id = :id and b.branchId = :#{@branchContext.currentBranchId}")
    Optional<VoucherBatch> findByIdForUpdate(@Param("id") UUID id);

    /*
     * What is still in the wild, for every batch, in one grouped query rather than four counts
     * per batch.
     *
     * The three states are exclusive and sum to issued, which is the property that makes the
     * screen readable: a code is REDEEMED, or it is EXPIRED, or it is OUTSTANDING. Expiry is
     * resolved against the caller's date rather than stored, because "expired" is a fact about
     * today -- a batch that is half outstanding this morning is fully expired tomorrow with
     * nothing having written to a single row.
     *
     * A redeemed code that has since passed its expiry counts as REDEEMED, not EXPIRED: it was
     * spent while it was good, and the question this screen answers is what happened to it.
     */
    @Query("""
            select b.id                                                              as batchId,
                   count(v)                                                          as issued,
                   count(case when v.redeemedAt is not null then 1 end)              as redeemed,
                   count(case when v.redeemedAt is null and v.expiresOn < :today
                              then 1 end)                                            as expired,
                   count(case when v.redeemedAt is null and v.expiresOn >= :today
                              then 1 end)                                            as outstanding
            from VoucherBatch b
            left join Voucher v on v.batchId = b.id
            where b.branchId = :#{@branchContext.currentBranchId}
            group by b.id
            """)
    List<BatchCountsProjection> countsByBatch(@Param("today") LocalDate today);

    interface BatchCountsProjection {
        UUID getBatchId();
        long getIssued();
        long getRedeemed();
        long getExpired();
        long getOutstanding();
    }
}
