package com.techcoder.sqlperf.customfield;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.audit.AuditService;
import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.customfield.CustomField.EntityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime-defined columns. Values are stored as text (ISO-8601 for dates) and validated against the
 * field's data type on write.
 */
@Service
public class CustomFieldService {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,99}");

    private final CustomFieldRepository fields;
    private final CustomFieldValueRepository values;
    private final AuditService audit;

    public CustomFieldService(CustomFieldRepository fields, CustomFieldValueRepository values, AuditService audit) {
        this.fields = fields;
        this.values = values;
        this.audit = audit;
    }

    public record FieldRequest(EntityType entityType, String fieldKey, String label, CustomField.DataType dataType,
                               String optionsCsv, Integer displayOrder, Boolean required, Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<CustomField> list(EntityType type) {
        return fields.findByEntityTypeOrderByDisplayOrderAscIdAsc(type);
    }

    @Transactional
    public CustomField create(FieldRequest req) {
        if (req.entityType() == null || req.dataType() == null || Texts.isBlank(req.label())) {
            throw new IllegalArgumentException("entityType, dataType and label are required");
        }
        String key = Texts.isBlank(req.fieldKey()) ? slug(req.label()) : req.fieldKey().trim();
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("fieldKey must be lower_snake_case: " + key);
        }
        if (fields.existsByEntityTypeAndFieldKey(req.entityType(), key)) {
            throw new IllegalArgumentException("Field '" + key + "' already exists for " + req.entityType());
        }
        if (req.dataType() == CustomField.DataType.ENUM && Texts.isBlank(req.optionsCsv())) {
            throw new IllegalArgumentException("ENUM fields need optionsCsv");
        }
        CustomField f = new CustomField();
        f.setEntityType(req.entityType());
        f.setFieldKey(key);
        apply(f, req);
        f.setDataType(req.dataType());
        f.setCreatedAt(LocalDateTime.now());
        f.setCreatedBy(CurrentUser.name());
        return fields.save(f);
    }

    /** Key, entity and data type are immutable once values may exist. */
    @Transactional
    public CustomField update(Long id, FieldRequest req) {
        CustomField f = fields.findById(id).orElseThrow(() -> new NotFoundException("Custom field", id));
        apply(f, req);
        return f;
    }

    private static void apply(CustomField f, FieldRequest req) {
        if (!Texts.isBlank(req.label())) {
            f.setLabel(req.label().trim());
        }
        if (req.optionsCsv() != null) {
            f.setOptionsCsv(Texts.trimToNull(req.optionsCsv()));
        }
        if (req.displayOrder() != null) {
            f.setDisplayOrder(req.displayOrder());
        }
        if (req.required() != null) {
            f.setRequired(req.required());
        }
        if (req.active() != null) {
            f.setActive(req.active());
        }
    }

    /** entityId -> (fieldKey -> value) for the active fields of a type. */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, String>> valuesFor(EntityType type, Collection<Long> entityIds) {
        Map<Long, Map<String, String>> out = new HashMap<>();
        if (entityIds.isEmpty()) {
            return out;
        }
        Map<Long, CustomField> byId = fields.findByEntityTypeOrderByDisplayOrderAscIdAsc(type).stream()
                .filter(CustomField::isActive)
                .collect(Collectors.toMap(CustomField::getId, Function.identity()));
        if (byId.isEmpty()) {
            return out;
        }
        for (CustomFieldValue v : values.findByFieldIdInAndEntityIdIn(byId.keySet(), entityIds)) {
            out.computeIfAbsent(v.getEntityId(), k -> new LinkedHashMap<>())
                    .put(byId.get(v.getFieldId()).getFieldKey(), v.getValueText());
        }
        return out;
    }

    /** Upserts the given key/value pairs; unknown keys are rejected, blank values clear the field. */
    @Transactional
    public void saveValues(EntityType type, Long entityId, Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return;
        }
        Map<String, CustomField> byKey = fields.findByEntityTypeOrderByDisplayOrderAscIdAsc(type).stream()
                .collect(Collectors.toMap(CustomField::getFieldKey, Function.identity()));
        for (Map.Entry<String, String> e : input.entrySet()) {
            CustomField f = byKey.get(e.getKey());
            if (f == null) {
                throw new IllegalArgumentException("Unknown custom field: " + e.getKey());
            }
            String newValue = validate(f, Texts.trimToNull(e.getValue()));
            CustomFieldValue v = values.findByFieldIdAndEntityId(f.getId(), entityId).orElse(null);
            String oldValue = v == null ? null : v.getValueText();
            if (v == null) {
                if (newValue == null) {
                    continue;
                }
                v = new CustomFieldValue();
                v.setFieldId(f.getId());
                v.setEntityId(entityId);
            }
            v.setValueText(newValue);
            v.setUpdatedAt(LocalDateTime.now());
            v.setUpdatedBy(CurrentUser.name());
            values.save(v);
            audit.changed(type.name(), entityId, "custom." + f.getFieldKey(), oldValue, newValue);
        }
    }

    private static String validate(CustomField f, String value) {
        if (value == null) {
            if (f.isRequired()) {
                throw new IllegalArgumentException(f.getLabel() + " is required");
            }
            return null;
        }
        try {
            switch (f.getDataType()) {
                case NUMBER -> new BigDecimal(value);
                case DATE -> {
                    if (value.length() > 10) {
                        LocalDateTime.parse(value);
                    } else {
                        LocalDate.parse(value);
                    }
                }
                case BOOLEAN -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException();
                    }
                    value = value.toLowerCase();
                }
                case ENUM -> {
                    boolean ok = Arrays.stream(f.getOptionsCsv().split(","))
                            .map(String::trim).anyMatch(value::equals);
                    if (!ok) {
                        throw new IllegalArgumentException();
                    }
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid value '" + value + "' for " + f.getLabel()
                    + " (" + f.getDataType() + ")");
        }
        return Texts.truncate(value, 4000);
    }

    static String slug(String label) {
        String s = label.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) {
            s = "f_" + s;
        }
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
