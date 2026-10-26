CREATE OR REPLACE PACKAGE BODY PKG_POLICY_LOAD AS

    PROCEDURE RUN_FULL_LOAD IS
    BEGIN
        -- This entry point orchestrates extraction, transformation, and loading.
        PKG_POLICY_TRANSFORM.RUN_TRANSFORM;
        LOAD_TARGET;
    END RUN_FULL_LOAD;

    PROCEDURE LOAD_TARGET IS
    BEGIN
        MERGE INTO TGT_POLICY t
        USING (
            SELECT x.POLICY_NO,
                   x.CALCULATED_EFFECTIVE_DATE,
                   x.POLICY_STATUS,
                   x.CUSTOMER_NAME,
                   x.PRODUCT_TYPE
              FROM STG_POLICY_TRANSFORMED x
        ) s
           ON (t.POLICY_NO = s.POLICY_NO)
        WHEN MATCHED THEN UPDATE SET
            t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE,
            t.POLICY_STATUS = s.POLICY_STATUS,
            t.CUSTOMER_NAME = s.CUSTOMER_NAME,
            t.PRODUCT_TYPE = s.PRODUCT_TYPE
        WHEN NOT MATCHED THEN INSERT (
            POLICY_NO,
            EFFECTIVE_DATE,
            POLICY_STATUS,
            CUSTOMER_NAME,
            PRODUCT_TYPE
        ) VALUES (
            s.POLICY_NO,
            s.CALCULATED_EFFECTIVE_DATE,
            s.POLICY_STATUS,
            s.CUSTOMER_NAME,
            s.PRODUCT_TYPE
        );
    END LOAD_TARGET;

END PKG_POLICY_LOAD;
/
