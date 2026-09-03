package com.supremebilliardshall.billiards_hall_system.dto;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor

public class APIResponse<T> {
    private T data;          // The actual payload
    private String message;  // A message for success or error
    private boolean success; // Flag to indicate if operation was successful

    // Machine-readable reason, only where the client has to branch on it rather than just
    // show the message. Omitted from the JSON when absent, so existing responses are unchanged.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public APIResponse(T data, String message, boolean success) {
        this(data, message, success, null);
    }

    public static <T> APIResponse<T> success(T data, String message) {
        return  new APIResponse<>(data, message, true);
    }

    public static <T> APIResponse<T> failure(String message) {
        return new APIResponse<>(null, message, false);
    }

    public static <T> APIResponse<T> failure(String message, String code) {
        return new APIResponse<>(null, message, false, code);
    }
}
