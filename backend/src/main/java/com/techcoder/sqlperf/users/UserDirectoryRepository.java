package com.techcoder.sqlperf.users;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDirectoryRepository extends JpaRepository<UserDirectoryEntry, String> {

    List<UserDirectoryEntry> findAllByOrderByUserGroupAscUserIdAsc();
}
