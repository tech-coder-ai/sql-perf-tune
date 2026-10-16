package com.techcoder.sqlperf.notification;

import java.util.List;

import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administration → Notifications: the e-mail outbox. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;
    private final NotificationRepository repo;

    public NotificationController(NotificationService service, NotificationRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /** Newest first; bodies are left out of the list. */
    public record Row(Long id, Long trackerId, Notification.Kind kind, String recipients, String subject,
                      Notification.Status status, String errorMessage, java.time.LocalDateTime createdAt,
                      java.time.LocalDateTime sentAt) {
        static Row of(Notification n) {
            return new Row(n.getId(), n.getTrackerId(), n.getKind(), n.getRecipients(), n.getSubject(), n.getStatus(),
                    n.getErrorMessage(), n.getCreatedAt(), n.getSentAt());
        }
    }

    @GetMapping
    public PageResponse<Row> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(service.list(page, size), Row::of);
    }

    @GetMapping("/{id}")
    public Notification get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Notification", id));
    }

    @GetMapping("/tracker/{trackerId}")
    public List<Row> forTracker(@PathVariable Long trackerId) {
        return service.forTracker(trackerId).stream().map(Row::of).toList();
    }
}
