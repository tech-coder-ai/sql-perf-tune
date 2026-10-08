package com.techcoder.sqlperf.users;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** User -> user group mapping used by the "bad queries by user group" report. */
@RestController
@RequestMapping("/api/users")
public class UserDirectoryController {

    private final UserDirectoryRepository repo;

    public UserDirectoryController(UserDirectoryRepository repo) {
        this.repo = repo;
    }

    public record UserRequest(String userId, String displayName, String userGroup, String department, Boolean active) {
    }

    @GetMapping
    public List<UserDirectoryEntry> list() {
        return repo.findAllByOrderByUserGroupAscUserIdAsc();
    }

    @PutMapping("/{userId}")
    @Transactional
    public UserDirectoryEntry upsert(@PathVariable String userId, @RequestBody UserRequest r) {
        return save(userId, r.displayName(), r.userGroup(), r.department(), r.active());
    }

    @DeleteMapping("/{userId}")
    @Transactional
    public void delete(@PathVariable String userId) {
        repo.delete(repo.findById(userId).orElseThrow(() -> new NotFoundException("User", userId)));
    }

    /** CSV with header user_id,user_group[,display_name][,department]; existing users are updated. */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Integer> upload(@RequestPart("file") MultipartFile file) throws IOException {
        int saved = 0;
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true).setTrim(true).get())) {
            Map<String, Integer> headers = parser.getHeaderMap();
            String userCol = find(headers, "userid", "user", "useris");
            if (userCol == null) {
                throw new IllegalArgumentException("The file needs a user_id column");
            }
            String groupCol = find(headers, "usergroup", "group", "team");
            String nameCol = find(headers, "displayname", "name");
            String deptCol = find(headers, "department", "dept");
            for (CSVRecord rec : parser) {
                String user = Texts.trimToNull(rec.get(userCol));
                if (user == null) {
                    continue;
                }
                save(user, nameCol == null ? null : rec.get(nameCol), groupCol == null ? null : rec.get(groupCol),
                        deptCol == null ? null : rec.get(deptCol), true);
                saved++;
            }
        }
        return Map.of("saved", saved);
    }

    private UserDirectoryEntry save(String userId, String name, String group, String dept, Boolean active) {
        UserDirectoryEntry e = repo.findById(userId).orElseGet(() -> {
            UserDirectoryEntry n = new UserDirectoryEntry();
            n.setUserId(userId.trim());
            return n;
        });
        e.setDisplayName(Texts.trimToNull(name));
        e.setUserGroup(Texts.trimToNull(group));
        e.setDepartment(Texts.trimToNull(dept));
        if (active != null) {
            e.setActive(active);
        }
        e.setUpdatedAt(LocalDateTime.now());
        e.setUpdatedBy(CurrentUser.name());
        return repo.save(e);
    }

    private static String find(Map<String, Integer> headers, String... aliases) {
        for (String h : headers.keySet()) {
            String k = h.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            for (String a : aliases) {
                if (k.equals(a)) {
                    return h;
                }
            }
        }
        return null;
    }
}
