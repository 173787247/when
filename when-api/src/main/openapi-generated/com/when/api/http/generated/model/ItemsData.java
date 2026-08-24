package com.when.api.http.generated.model;

import java.util.List;

public record ItemsData<T>(List<T> items) {
    public ItemsData { items = List.copyOf(items); }
}
