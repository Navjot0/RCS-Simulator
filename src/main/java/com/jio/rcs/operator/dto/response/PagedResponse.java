package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Minimal hand-rolled pagination envelope. Spring Data's Page/Pageable are
 * intentionally not used - this service has no database, so there is no
 * Spring Data dependency on the classpath at all.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static <T> PagedResponse<T> of(List<T> all, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : size;
        int totalElements = all.size();
        int totalPages = (int) Math.ceil(totalElements / (double) safeSize);
        int fromIndex = Math.min(safePage * safeSize, totalElements);
        int toIndex = Math.min(fromIndex + safeSize, totalElements);
        return PagedResponse.<T>builder()
                .content(all.subList(fromIndex, toIndex))
                .page(safePage)
                .size(safeSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }
}
