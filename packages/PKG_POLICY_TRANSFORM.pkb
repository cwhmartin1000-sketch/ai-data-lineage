CREATE OR REPLACE PACKAGE BODY PKG_POLICY_TRANSFORM AS

    PROCEDURE RUN_TRANSFORM IS
    BEGIN
        -- Cross-package procedure call: extraction must populate STG_POLICY first.
        PKG_POLICY_EXTRACT.RUN_EXTRACT;
        BUILD_TRANSFORMED_ROWS;
    END RUN_TRANSFORM;

    PROCEDURE BUILD_TRANSFORMED_ROWS IS
    BEGIN
        DELETE FROM STG_POLICY_TRANSFORMED;

        INSERT INTO STG_POLICY_TRANSFORMED (
            POLICY_NO,
            CALCULATED_EFFECTIVE_DATE,
            POLICY_STATUS,
            CUSTOMER_NAME,
            PRODUCT_TYPE
        )
        SELECT s.POLICY_NO,
               CASE
                   WHEN s.POLICY_STATUS = 'ACTIVE'
                       THEN TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
                   WHEN s.POLICY_STATUS = 'PENDING'
                       THEN TO_DATE(
                           TO_CHAR(NVL(s.REQUESTED_START_DATE, s.START_DATE + 30), 'YYYY-MM-DD'),
                           'YYYY-MM-DD'
                       )
                   ELSE TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
               END AS CALCULATED_EFFECTIVE_DATE,
               s.POLICY_STATUS,
               NVL(s.CUSTOMER_NAME, 'UNKNOWN CUSTOMER'),
               NVL(s.PRODUCT_TYPE, 'UNKNOWN PRODUCT')
          FROM STG_POLICY s;
    END BUILD_TRANSFORMED_ROWS;

END PKG_POLICY_TRANSFORM;
/
