-- Seed: default Impala optimization prompt (diagram step 7).
-- Placeholders are replaced by PromptRenderer.
INSERT INTO SPT_PROMPT_TEMPLATE (NAME, SQL_ENGINE, VERSION_NO, TEMPLATE_TEXT, ACTIVE, NOTES, CREATED_AT, CREATED_BY)
VALUES ('impala-default', 'IMPALA', 1, 'You are a senior Cloudera Impala performance engineer. Rewrite the SQL below so it returns exactly the same result set while running faster and using fewer cluster resources.

Consider: partition pruning, predicate push-down, join order and join strategy (BROADCAST vs SHUFFLE), missing or stale statistics (COMPUTE STATS), avoiding SELECT *, avoiding functions on partition columns, replacing correlated sub-queries, reducing data skew, and spilling to disk.

## Bad SQL
{{bad_sql}}

## Table DDL
{{ddl}}

## Table row counts
{{row_counts}}

## Explain plan
{{explain}}

## Parsed profile
{{profile_summary}}

## Execution summary
{{exec_summary}}

Respond with two sections exactly:
### CHANGE NARRATIVE
A numbered list of every change and why it helps.
### OPTIMIZED SQL
A single ```sql fenced block containing only the rewritten query.', 1, 'Initial prompt', SYSTIMESTAMP, 'system');
