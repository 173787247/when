package com.when.api.http.generated.model;

import jakarta.validation.Valid;

public record SinkConfig(@Valid HttpSinkConfig http, @Valid KafkaSinkConfig kafka) {
}
