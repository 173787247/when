package com.when.cluster.controller;

final class Text {
    private Text() {
    }

    static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String segment(String value, String field) {
        value = require(value, field);
        if (value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + " must be a key segment");
        }
        return value;
    }

    static String optional(String value, String field) {
        return value == null ? null : require(value, field);
    }
}
