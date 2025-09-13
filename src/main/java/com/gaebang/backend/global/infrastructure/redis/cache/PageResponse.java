package com.gaebang.backend.global.infrastructure.redis.cache;

import lombok.*;
import org.springframework.data.domain.*;

import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PageResponse<T> {
    private List<T> content;
    
    private PageableWire pageable;
    private boolean last;
    private int totalPages;
    private long totalElements;
    private int size;
    private int number;
    private SortWire sort;   // flags only (orders[] 제외)
    private boolean first;
    private int numberOfElements;
    private boolean empty;

    public static <T> PageResponse<T> from(Page<T> page) {
        PageResponse<T> dto = new PageResponse<>();
        dto.setContent(page.getContent());
        dto.setPageable(PageableWire.from(page.getPageable()));
        dto.setLast(page.isLast());
        dto.setTotalPages(page.getTotalPages());
        dto.setTotalElements(page.getTotalElements());
        dto.setSize(page.getSize());
        dto.setNumber(page.getNumber());
        dto.setSort(SortWire.from(page.getSort()));
        dto.setFirst(page.isFirst());
        dto.setNumberOfElements(page.getNumberOfElements());
        dto.setEmpty(page.isEmpty());
        return dto;
    }

    public Page<T> toPageUsingRequest(Pageable requestPageable) {
        return new PageImpl<>(content, requestPageable, totalElements);
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class PageableWire {
        private int pageNumber;
        private int pageSize;
        private SortWire sort;  // flags only
        private long offset;
        private boolean paged;
        private boolean unpaged;

        public static PageableWire from(Pageable pb) {
            return new PageableWire(
                pb.getPageNumber(),
                pb.getPageSize(),
                SortWire.from(pb.getSort()),
                pb.getOffset(),
                pb.isPaged(),
                pb.isUnpaged()
            );
        }
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SortWire {
        private boolean empty;
        private boolean sorted;
        private boolean unsorted;

        public static SortWire from(Sort sort) {
            return new SortWire(sort.isEmpty(), sort.isSorted(), sort.isUnsorted());
        }
    }

    // (옵션 확장용)
    // @JsonInclude(JsonInclude.Include.NON_EMPTY)
    // private String sortSpec; // "createdAt,DESC|id,DESC"
}