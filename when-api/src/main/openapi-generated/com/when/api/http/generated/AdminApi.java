package com.when.api.http.generated;

import com.when.api.http.generated.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Generated Spring interface for the Admin tag in openapi/when-v1.yaml. */
public interface AdminApi {
    @GetMapping(path = "/admin/v1/timewheels", produces = "application/json")
    ResponseEntity<ApiEnvelope<ItemsData<TimeWheelView>>> listTimeWheels(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId);

    @PostMapping(path = "/admin/v1/timewheels", consumes = "application/json", produces = "application/json")
    ResponseEntity<ApiEnvelope<CreateTimeWheelsData>> createTimeWheels(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestHeader(name = "Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String idempotencyKey,
            @Valid @RequestBody CreateTimeWheelsRequest request);

    @GetMapping(path = "/admin/v1/messages", produces = "application/json")
    ResponseEntity<ApiEnvelope<MessagePageData>> listMessages(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestParam(name = "status", required = false) MessageStatus status,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "sink_type", required = false) SinkType sinkType,
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) Integer limit);

    @GetMapping(path = "/admin/v1/messages/{id}", produces = "application/json")
    ResponseEntity<ApiEnvelope<MessageDetails>> getMessageDetails(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @PathVariable("id") String id);

    @GetMapping(path = "/admin/v1/cluster/nodes", produces = "application/json")
    ResponseEntity<ApiEnvelope<ItemsData<ClusterNodeView>>> listClusterNodes(
            @RequestHeader(name = "X-Request-Id", required = false) String requestId);
}
