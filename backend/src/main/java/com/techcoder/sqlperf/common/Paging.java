package com.techcoder.sqlperf.common;

import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {

    private static final int MAX_SIZE = 500;

    private Paging() {
    }

    /**
     * @param sort "field,asc|desc"; fields outside {@code allowed} fall back to {@code defaultSort}
     */
    public static Pageable of(int page, int size, String sort, Set<String> allowed, Sort defaultSort) {
        Sort s = defaultSort;
        if (!Texts.isBlank(sort)) {
            String[] parts = sort.split(",");
            if (allowed.contains(parts[0])) {
                Sort.Direction dir = parts.length > 1 && parts[1].equalsIgnoreCase("desc")
                        ? Sort.Direction.DESC : Sort.Direction.ASC;
                s = Sort.by(dir, parts[0]).and(Sort.by("id"));
            }
        }
        return PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_SIZE), s);
    }
}
