package com.techcoder.sqlperf.lookup;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LookupRepository extends JpaRepository<Lookup, Long> {

    List<Lookup> findAllByOrderByCategoryAscSortOrderAscIdAsc();

    List<Lookup> findByCategoryOrderBySortOrderAscIdAsc(String category);

    boolean existsByCategoryAndValue(String category, String value);
}
