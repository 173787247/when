package com.when.ingress.grpc;

import com.google.protobuf.ByteString;
import com.when.api.application.SubmitCommand;
import com.when.api.grpc.SubmitRequest;
import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import com.when.core.MessageStatus;
import com.when.core.SinkType;

final class GrpcMessageMapper {
    private GrpcMessageMapper() {
    }

    static SubmitRequest toRequest(SubmitCommand command) {
        var sinkConfig = com.when.common.proto.SinkConfig.newBuilder();
        if (command.sinkConfig() instanceof HttpSinkConfig http) {
            sinkConfig.setHttp(com.when.common.proto.HttpSinkConfig.newBuilder()
                    .setUrl(http.url())
                    .setMethod(nullToEmpty(http.method()))
                    .putAllHeaders(http.headers())
                    .setTimeoutMs(http.timeoutMs()));
        } else if (command.sinkConfig() instanceof KafkaSinkConfig kafka) {
            sinkConfig.setKafka(com.when.common.proto.KafkaSinkConfig.newBuilder()
                    .setBootstrapServers(kafka.bootstrapServers())
                    .setTopic(kafka.topic())
                    .setKey(nullToEmpty(kafka.key()))
                    .putAllHeaders(kafka.headers()));
        } else {
            throw new IllegalArgumentException("unsupported sink configuration");
        }
        return SubmitRequest.newBuilder()
                .setDeliverAt(command.deliverAt())
                .setSinkType(toProto(command.sinkType()))
                .setSinkConfig(sinkConfig)
                .setPayload(ByteString.copyFrom(command.payload()))
                .setBusinessTag(nullToEmpty(command.businessTag()))
                .build();
    }

    static MessageStatus fromProto(com.when.common.proto.MessageStatus status) {
        return switch (status) {
            case PENDING -> MessageStatus.PENDING;
            case DELIVERING -> MessageStatus.DELIVERING;
            case DELIVERED -> MessageStatus.DELIVERED;
            case FAILED -> MessageStatus.FAILED;
            case CANCELLED -> MessageStatus.CANCELLED;
            case MESSAGE_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalStateException("remote node returned an unspecified message status");
        };
    }

    private static com.when.common.proto.SinkType toProto(SinkType type) {
        return switch (type) {
            case HTTP -> com.when.common.proto.SinkType.HTTP;
            case KAFKA -> com.when.common.proto.SinkType.KAFKA;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
