-- Run after the packages have been compiled and PKG_POLICY_LOAD.RUN_FULL_LOAD completes.

-- Expected: 12 rows; includes ACTIVE, PENDING, and LAPSED.
SELECT POLICY_STATUS, COUNT(*) AS POLICY_COUNT
  FROM TGT_POLICY
 GROUP BY POLICY_STATUS
 ORDER BY POLICY_STATUS;

-- Compare source dates, optional requested dates, and final effective dates.
-- ACTIVE uses POLICY_START_DT. PENDING uses requested date when present,
-- otherwise POLICY_START_DT + 30 days. LAPSED uses POLICY_START_DT.
SELECT s.POLICY_ID,
       s.POLICY_STATUS,
       s.POLICY_START_DT,
       s.REQUESTED_EFFECTIVE_DT,
       t.EFFECTIVE_DATE,
       CASE
           WHEN s.POLICY_STATUS = 'PENDING'
               THEN NVL(s.REQUESTED_EFFECTIVE_DT, s.POLICY_START_DT + 30)
           ELSE s.POLICY_START_DT
       END AS EXPECTED_EFFECTIVE_DATE,
       CASE
           WHEN t.EFFECTIVE_DATE = CASE
                   WHEN s.POLICY_STATUS = 'PENDING'
                       THEN NVL(s.REQUESTED_EFFECTIVE_DT, s.POLICY_START_DT + 30)
                   ELSE s.POLICY_START_DT
               END
               THEN 'MATCH'
           ELSE 'MISMATCH'
       END AS CHECK_RESULT
  FROM SRC_POLICY s
  JOIN TGT_POLICY t ON t.POLICY_NO = s.POLICY_ID
 ORDER BY s.POLICY_ID;

-- Expected: 0 rows. Every target row should have its source policy.
SELECT t.POLICY_NO
  FROM TGT_POLICY t
  LEFT JOIN SRC_POLICY s ON s.POLICY_ID = t.POLICY_NO
 WHERE s.POLICY_ID IS NULL;

-- Inspect the extraction joins and the transformed staging result.
SELECT POLICY_NO, START_DATE, REQUESTED_START_DATE, POLICY_STATUS,
       CUSTOMER_NAME, PRODUCT_TYPE
  FROM STG_POLICY
 ORDER BY POLICY_NO;

SELECT POLICY_NO, CALCULATED_EFFECTIVE_DATE, POLICY_STATUS,
       CUSTOMER_NAME, PRODUCT_TYPE
  FROM STG_POLICY_TRANSFORMED
 ORDER BY POLICY_NO;

-- The similarly named archive column is a distractor and is not loaded.
SELECT a.POLICY_ID, a.POLICY_START_DT AS ARCHIVE_POLICY_START_DT
  FROM SRC_POLICY_ARCHIVE a
 ORDER BY a.ARCHIVE_ID;
