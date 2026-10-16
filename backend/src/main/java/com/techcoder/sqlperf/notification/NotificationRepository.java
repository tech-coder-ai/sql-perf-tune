package com.techcoder.sqlperf.notification;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findAllByOrderByIdDesc(Pageable pageable);

    List<Notification> findByTrackerIdOrderByIdDesc(Long trackerId);
}
