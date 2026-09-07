package com.supremebilliardshall.billiards_hall_system.dto.bill;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Recording a debt rather than taking money for it.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveUnpaidRequestDTO {

    // Who owes it. Optional HERE and required by the service, because the rule is conditional
    // on state a bean validator cannot see: a session whose thread already carries a staff note
    // needs no second one, and a session with an empty thread cannot be left unpaid at all.
    // The 280 limit matches session_note_body_chk, so an over-long note is refused by the same
    // number in both places.
    @Size(max = 280, message = "The note must be at most 280 characters")
    private String note;

    // The version the client read. A stale one is a 409, exactly as at checkout: two tabs must
    // not be able to leave the same bill unpaid twice, nor leave one unpaid that has since been
    // paid for.
    private Integer billVersion;
}
