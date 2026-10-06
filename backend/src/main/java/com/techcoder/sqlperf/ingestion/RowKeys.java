package com.techcoder.sqlperf.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.config.SptProperties.RowIdentity;
import com.techcoder.sqlperf.log.QueryLog;
import org.springframework.stereotype.Component;

/** Computes the identity key that decides whether a log row was already loaded. */
@Component
public class RowKeys {

    private final RowIdentity identity;

    public RowKeys(SptProperties props) {
        this.identity = props.ingestion().rowIdentity();
    }

    public String of(QueryLog l) {
        String material;
        if (identity == RowIdentity.SEQ_ID && l.getSeqId() != null) {
            material = "seq|" + l.getSqlEngine() + "|" + l.getSeqId();
        } else {
            material = String.join("\u001f",
                    "content", l.getSqlEngine(),
                    Objects.toString(l.getSeqId(), ""),
                    Objects.toString(l.getUserId(), ""),
                    Objects.toString(l.getStartTime(), ""),
                    Objects.toString(l.getEndTime(), ""),
                    Objects.toString(l.getExecutedQuery(), ""),
                    Objects.toString(l.getUserQuery(), ""));
        }
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
