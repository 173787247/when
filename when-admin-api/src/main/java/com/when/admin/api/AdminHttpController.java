package com.when.admin.api;

import com.when.api.http.RequestIds;
import com.when.api.http.generated.AdminApi;
import com.when.api.http.generated.model.*;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** OpenAPI-generated management interface implementation with no persistence logic. */
@RestController
public final class AdminHttpController implements AdminApi {
    private final AdminQueryService service;

    public AdminHttpController(AdminQueryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public ResponseEntity<ApiEnvelope<ItemsData<TimeWheelView>>> listTimeWheels(String ignoredRequestId) {
        String requestId = RequestIds.current();
        return ResponseEntity.ok().header(RequestIds.HEADER, requestId)
                .body(ApiEnvelope.ok(requestId, service.listTimeWheels()));
    }

    @Override
    public ResponseEntity<ApiEnvelope<CreateTimeWheelsData>> createTimeWheels(
            String ignoredRequestId, String idempotencyKey, CreateTimeWheelsRequest request) {
        AdminQueryService.CreatedTimeWheels result =
                service.createTimeWheels(request.count(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(RequestIds.HEADER, RequestIds.current())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(ApiEnvelope.ok(RequestIds.current(), result.data()));
    }

    @Override
    public ResponseEntity<ApiEnvelope<MessagePageData>> listMessages(
            String ignoredRequestId,
            MessageStatus status,
            String tag,
            SinkType sinkType,
            Long from,
            Long to,
            String cursor,
            Integer limit) {
        String requestId = RequestIds.current();
        return ResponseEntity.ok().header(RequestIds.HEADER, requestId).body(ApiEnvelope.ok(requestId,
                service.listMessages(status, sinkType, tag, from, to, cursor, limit)));
    }

    @Override
    public ResponseEntity<ApiEnvelope<MessageDetails>> getMessageDetails(
            String ignoredRequestId, String id) {
        String requestId = RequestIds.current();
        return ResponseEntity.ok().header(RequestIds.HEADER, requestId)
                .body(ApiEnvelope.ok(requestId, service.details(id)));
    }

    @Override
    public ResponseEntity<ApiEnvelope<ItemsData<ClusterNodeView>>> listClusterNodes(
            String ignoredRequestId) {
        String requestId = RequestIds.current();
        return ResponseEntity.ok().header(RequestIds.HEADER, requestId)
                .body(ApiEnvelope.ok(requestId, service.listNodes()));
    }
}
