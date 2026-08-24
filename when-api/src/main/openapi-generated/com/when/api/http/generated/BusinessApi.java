package com.when.api.http.generated;

import com.when.api.http.generated.model.ApiEnvelope;
import com.when.api.http.generated.model.CancelMessageData;
import com.when.api.http.generated.model.MessageSummary;
import com.when.api.http.generated.model.SubmitMessageData;
import com.when.api.http.generated.model.SubmitMessageRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Generated Spring interface for the Business tag in openapi/when-v1.yaml. */
public interface BusinessApi {
    @PostMapping(path = "/api/v1/messages", consumes = "application/json", produces = "application/json")
    ResponseEntity<ApiEnvelope<SubmitMessageData>> submitMessage(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody SubmitMessageRequest request);

    @GetMapping(path = "/api/v1/messages/{id}", produces = "application/json")
    ResponseEntity<ApiEnvelope<MessageSummary>> queryMessage(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @PathVariable("id") @Size(min = 1, max = 128) String id);

    @DeleteMapping(path = "/api/v1/messages/{id}", produces = "application/json")
    ResponseEntity<ApiEnvelope<CancelMessageData>> cancelMessage(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @PathVariable("id") @Size(min = 1, max = 128) String id);
}
