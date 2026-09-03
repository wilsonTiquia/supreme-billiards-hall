package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.time.ServerTimeResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

// The client computes its clock offset from this. Session and floor-view responses carry
// serverNow too, so the timer needs no extra round trip.
@RestController
@RequestMapping("/api/v1/time")
public class TimeController {

    @GetMapping
    public ResponseEntity<APIResponse<ServerTimeResponseDTO>> getServerTime() {
        ServerTimeResponseDTO serverTime = new ServerTimeResponseDTO(OffsetDateTime.now());
        return ResponseEntity.
                ok(APIResponse.success(
                        serverTime,
                        "Server time fetched successfully"));
    }

}
