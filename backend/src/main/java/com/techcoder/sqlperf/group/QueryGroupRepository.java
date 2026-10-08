package com.techcoder.sqlperf.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QueryGroupRepository extends JpaRepository<QueryGroup, Long>, JpaSpecificationExecutor<QueryGroup> {

    Optional<QueryGroup> findBySqlEngineAndFingerprint(String sqlEngine, String fingerprint);

    List<QueryGroup> findBySqlEngineAndFingerprintIn(String sqlEngine, Collection<String> fingerprints);
}
