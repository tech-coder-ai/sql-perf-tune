package com.techcoder.sqlperf.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.Texts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(String entityType, Long entityId) {
        repo.save(event(entityType, entityId, "CREATE", null, null, null));
    }

    /** Records a field change; no-op when the value did not change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(String entityType, Long entityId, String field, Object oldValue, Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        repo.save(event(entityType, entityId, "UPDATE", field, str(oldValue), str(newValue)));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(String entityType, Long entityId) {
        return repo.findByEntityTypeAndEntityIdOrderByChangedAtDescIdDesc(entityType, entityId);
    }

    private static AuditEvent event(String type, Long id, String action, String field, String oldV, String newV) {
        AuditEvent e = new AuditEvent();
        e.setEntityType(type);
        e.setEntityId(id);
        e.setAction(action);
        e.setFieldName(field);
        e.setOldValue(oldV);
        e.setNewValue(newV);
        e.setChangedAt(LocalDateTime.now());
        e.setChangedBy(CurrentUser.name());
        return e;
    }

    private static String str(Object o) {
        return o == null ? null : Texts.truncate(o.toString(), 4000);
    }
}
