package com.techcoder.sqlperf.tracker;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.customfield.CustomField;
import com.techcoder.sqlperf.customfield.CustomFieldService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** Streams the tracking screen to .xlsx (all standard columns followed by active custom fields). */
@Component
public class TrackerExcelExporter {

    /** Excel's per-cell limit. */
    private static final int MAX_CELL = 32_767;

    private final CustomFieldService customFields;

    public TrackerExcelExporter(CustomFieldService customFields) {
        this.customFields = customFields;
    }

    public void write(List<TrackerDto> rows, OutputStream out) throws IOException {
        RecordComponent[] comps = TrackerDto.class.getRecordComponents();
        List<RecordComponent> cols = new ArrayList<>();
        for (RecordComponent rc : comps) {
            if (!rc.getName().equals("customFields") && !rc.getName().equals("version")) {
                cols.add(rc);
            }
        }
        List<CustomField> custom = customFields.list(CustomField.EntityType.TRACKER).stream()
                .filter(CustomField::isActive).toList();

        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Sheet sheet = wb.createSheet("Tracker");
            CellStyle header = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);

            Row h = sheet.createRow(0);
            int c = 0;
            for (RecordComponent rc : cols) {
                Cell cell = h.createCell(c++);
                cell.setCellValue(snake(rc.getName()));
                cell.setCellStyle(header);
            }
            for (CustomField f : custom) {
                Cell cell = h.createCell(c++);
                cell.setCellValue(f.getLabel());
                cell.setCellStyle(header);
            }
            sheet.createFreezePane(2, 1);

            int r = 1;
            for (TrackerDto dto : rows) {
                Row row = sheet.createRow(r++);
                c = 0;
                for (RecordComponent rc : cols) {
                    set(row.createCell(c++), read(rc, dto));
                }
                for (CustomField f : custom) {
                    set(row.createCell(c++), dto.customFields().get(f.getFieldKey()));
                }
            }
            wb.write(out);
        }
    }

    private static void set(Cell cell, Object v) {
        switch (v) {
            case null -> cell.setBlank();
            case Number n -> cell.setCellValue(n.doubleValue());
            case LocalDateTime d -> cell.setCellValue(d.toString().replace('T', ' '));
            default -> cell.setCellValue(Texts.truncate(v.toString(), MAX_CELL));
        }
    }

    private static Object read(RecordComponent rc, Object o) {
        try {
            return rc.getAccessor().invoke(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
