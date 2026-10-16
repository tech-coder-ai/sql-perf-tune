package com.techcoder.sqlperf.autorun;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ThemeFixRepository extends JpaRepository<ThemeFix, Long> {

    List<ThemeFix> findTop50ByOrderByIdDesc();
}
