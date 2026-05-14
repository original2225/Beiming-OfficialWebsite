package dev.beiming.community;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int pageSize, int total) {
}
