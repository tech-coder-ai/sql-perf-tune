package com.techcoder.sqlperf.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** Reads query logs from CSV (any delimiter auto-detected among , ; | tab) or Excel (.xlsx / .xls, first sheet). */
@Component
public class LogFileParser {

    public void parseCsv(InputStream in, LogRowSink sink) throws IOException {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        java.io.BufferedReader br = new java.io.BufferedReader(reader);
        br.mark(64 * 1024);
        char delimiter = detectDelimiter(br.readLine());
        br.reset();

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (CSVParser parser = CSVParser.parse(br, format)) {
            List<Optional<LogColumn>> columns = parser.getHeaderNames().stream().map(LogColumn::fromHeader).toList();
            requireQueryColumn(columns);
            var it = parser.iterator();
            long row = 1;
            while (true) {
                CSVRecord rec;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    rec = it.next();
                } catch (RuntimeException e) {
                    sink.reject(row + 1, "Malformed CSV: " + e.getMessage());
                    break;
                }
                row = rec.getRecordNumber() + 1;
                RawLogRecord r = new RawLogRecord(row);
                for (int i = 0; i < columns.size() && i < rec.size(); i++) {
                    final int idx = i;
                    columns.get(i).ifPresent(c -> r.put(c, rec.get(idx)));
                }
                if (!r.isEmpty()) {
                    sink.accept(r);
                }
            }
        }
    }

    public void parseExcel(InputStream in, LogRowSink sink) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("The first sheet is empty");
            }
            List<Optional<LogColumn>> columns = new ArrayList<>();
            for (int c = 0; c < header.getLastCellNum(); c++) {
                columns.add(LogColumn.fromHeader(fmt.formatCellValue(header.getCell(c))));
            }
            requireQueryColumn(columns);
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                RawLogRecord rec = new RawLogRecord(r + 1L);
                for (int c = 0; c < columns.size(); c++) {
                    Optional<LogColumn> col = columns.get(c);
                    Cell cell = row.getCell(c);
                    if (col.isEmpty() || cell == null) {
                        continue;
                    }
                    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        LocalDateTime d = cell.getLocalDateTimeCellValue();
                        rec.putDate(col.get(), d);
                    } else if (cell.getCellType() == CellType.NUMERIC) {
                        double d = cell.getNumericCellValue();
                        rec.put(col.get(), d == Math.rint(d) && Math.abs(d) < 1e15
                                ? String.valueOf((long) d) : String.valueOf(d));
                    } else {
                        rec.put(col.get(), fmt.formatCellValue(cell));
                    }
                }
                if (!rec.isEmpty()) {
                    sink.accept(rec);
                }
            }
        }
    }

    private static void requireQueryColumn(List<Optional<LogColumn>> columns) {
        boolean hasQuery = columns.stream().flatMap(Optional::stream)
                .anyMatch(c -> c == LogColumn.EXECUTED_QUERY || c == LogColumn.USER_QUERY);
        if (!hasQuery) {
            throw new IllegalArgumentException(
                    "The file must have an executed_query or user_query column (header row expected)");
        }
    }

    static char detectDelimiter(String headerLine) {
        if (headerLine == null) {
            return ',';
        }
        char best = ',';
        long bestCount = -1;
        for (char c : new char[] {',', ';', '|', '\t'}) {
            long n = headerLine.chars().filter(ch -> ch == c).count();
            if (n > bestCount) {
                best = c;
                bestCount = n;
            }
        }
        return best;
    }
}
