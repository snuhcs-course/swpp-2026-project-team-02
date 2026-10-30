# Generated Study Plans

Each CSV starts as a saved Planning API response. A successful persistent
adjustment replaces that same file with the updated rows, so it represents the
latest saved plan rather than an immutable generation snapshot. Older plans can
contain topics no longer present in the active certification catalog. Generate
a new plan to see the current catalog and agent behavior. After a new plan is saved, the API
attempts to keep the 100 most recent matching generated plan files by deleting
older ones. Cleanup is best-effort if an old file is locked or otherwise cannot
be removed. A removed file's download URL returns not found.
