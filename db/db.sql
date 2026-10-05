-- =====================================================================
-- ABC Bank UPI failure agent demo | MySQL 8.0.16+
-- Per-event design: REST API receives one error log -> classify_error -> create_incident
-- WARNING: drops the whole abcbank_upi database (all old tables/views/data).
-- =====================================================================
DROP DATABASE IF EXISTS abcbank_upi;
CREATE DATABASE abcbank_upi CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE abcbank_upi;

-- ---------------------------------------------------------------
-- 1. Response-code rules (reference data for classify_error / RAG seed)
--    Same code can mean different things per stage (e.g. 91).
-- ---------------------------------------------------------------
CREATE TABLE upi_response_rules (
                                    id               INT AUTO_INCREMENT PRIMARY KEY,
                                    resp_code        VARCHAR(5)   NOT NULL,
                                    stage            VARCHAR(30)  NOT NULL,
                                    description      VARCHAR(255) NOT NULL,
                                    category         ENUM('TECHNICAL_CBS','INFRA_NPCI','INFRA_HSM','ISSUER_DECLINE',
                        'BENEFICIARY_DECLINE','CUSTOMER_DECLINE','OTHER') NOT NULL,
                                    default_severity ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL,
                                    create_incident  BOOLEAN      NOT NULL,
                                    runbook_ref      VARCHAR(100) NULL,
                                    guidance         VARCHAR(500) NULL,
                                    UNIQUE KEY uq_code_stage (resp_code, stage)
);

INSERT INTO upi_response_rules
(resp_code, stage, description, category, default_severity, create_incident, runbook_ref, guidance) VALUES
                                                                                                        ('51','CBS_DEBIT','Insufficient funds in remitter account','CUSTOMER_DECLINE','LOW',FALSE,'RB-CUST-001','Normal business decline. No incident unless volume is abnormal.'),
                                                                                                        ('ZM','REMITTER_AUTH','Invalid MPIN entered by customer','CUSTOMER_DECLINE','LOW',FALSE,'RB-CUST-002','Customer error. Escalate only on abnormal spike (possible fraud).'),
                                                                                                        ('U16','RISK_CHECK','Risk / daily limit threshold exceeded','CUSTOMER_DECLINE','LOW',FALSE,'RB-CUST-003','Policy decline working as designed.'),
                                                                                                        ('ZH','VPA_RESOLVE','Invalid payee VPA','BENEFICIARY_DECLINE','LOW',FALSE,'RB-CUST-004','Input error by customer.'),
                                                                                                        ('U19','COLLECT_EXPIRY','Collect request expired','CUSTOMER_DECLINE','LOW',FALSE,'RB-CUST-005','Payer did not approve in time.'),
                                                                                                        ('91','CBS_DEBIT','CBS did not respond within timeout; txn marked DEEMED','TECHNICAL_CBS','HIGH',TRUE,'RB-CBS-017','Check CBS host health, DB locks, batch overlap. Customer may be debited; reconcile deemed txns.'),
                                                                                                        ('91','BENEFICIARY_CREDIT','Beneficiary bank not responding','BENEFICIARY_DECLINE','MEDIUM',TRUE,'RB-BEN-004','Check NPCI bank-down advisories. Issue is on the beneficiary bank side.'),
                                                                                                        ('91','CBS_REVERSAL','Auto-reversal to customer failed; debit not reversed','TECHNICAL_CBS','CRITICAL',TRUE,'RB-CBS-021','Customer money stuck. Manual reversal needed. Treat as critical.'),
                                                                                                        ('96','HSM_VERIFY','HSM connection failure during PIN verification','INFRA_HSM','HIGH',TRUE,'RB-HSM-003','Check HSM host and network; failover to secondary HSM.'),
                                                                                                        ('96','CBS_DEBIT','CBS system malfunction','TECHNICAL_CBS','HIGH',TRUE,'RB-CBS-018','Check CBS application logs and DB connectivity.'),
                                                                                                        ('U30','NPCI_FORWARD','Request to NPCI failed (timeout / connection pool exhaustion)','INFRA_NPCI','CRITICAL',TRUE,'RB-NPCI-002','All outbound UPI traffic at risk. Check connection pool, NPCI link, network.');

-- ---------------------------------------------------------------
-- 2. Incoming error events (audit trail written by the REST API)
--    reason_norm is auto-filled by a trigger; error_signature is generated.
--    Classification columns are filled by the agent after classify_error.
-- ---------------------------------------------------------------
CREATE TABLE upi_error_logs (
                                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                log_ts          DATETIME(3)  NOT NULL,
                                level           ENUM('INFO','WARN','ERROR') NOT NULL,
                                component       VARCHAR(40)  NOT NULL,
                                txn_id          VARCHAR(40)  NULL,
                                rrn             VARCHAR(12)  NULL,
                                stage           VARCHAR(30)  NOT NULL,
                                resp_code       VARCHAR(5)   NOT NULL,
                                reason          VARCHAR(255) NOT NULL,
                                reason_norm     VARCHAR(255) NOT NULL DEFAULT '',
                                payer_vpa       VARCHAR(60)  NULL,
                                payee_vpa       VARCHAR(60)  NULL,
                                amount          DECIMAL(12,2) NULL,
                                extra_json      JSON NULL,
                                error_signature VARCHAR(255)
                                    GENERATED ALWAYS AS (CONCAT(component,'|',stage,'|',resp_code,'|',reason_norm)) STORED,
    -- filled after classification
                                category        ENUM('TECHNICAL_CBS','INFRA_NPCI','INFRA_HSM','ISSUER_DECLINE',
                       'BENEFICIARY_DECLINE','CUSTOMER_DECLINE','OTHER') NULL,
                                severity        ENUM('LOW','MEDIUM','HIGH','CRITICAL') NULL,
                                confidence      DECIMAL(3,2) NULL,
                                probable_cause  VARCHAR(500) NULL,
                                runbook_ref     VARCHAR(100) NULL,
                                incident_id     BIGINT NULL,
                                classified_at   DATETIME(3) NULL,
                                received_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                                KEY idx_ts (log_ts),
                                KEY idx_sig (error_signature),
                                KEY idx_rrn (rrn)
);

CREATE TRIGGER trg_logs_reason_norm BEFORE INSERT ON upi_error_logs
    FOR EACH ROW SET NEW.reason_norm = REGEXP_REPLACE(NEW.reason, '[0-9]+', '#');

-- ---------------------------------------------------------------
-- 3. Incidents (ids start at 10001; v_incidents shows INC-xxxxx)
-- ---------------------------------------------------------------
CREATE TABLE incidents (
                           id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                           title            VARCHAR(200) NOT NULL,
                           category         ENUM('TECHNICAL_CBS','INFRA_NPCI','INFRA_HSM','ISSUER_DECLINE',
                        'BENEFICIARY_DECLINE','CUSTOMER_DECLINE','OTHER') NOT NULL,
                           severity         ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL,
                           status           ENUM('OPEN','IN_PROGRESS','RESOLVED') NOT NULL DEFAULT 'OPEN',
                           error_signature  VARCHAR(255) NOT NULL,
                           affected_count   INT NOT NULL DEFAULT 1,
                           sample_rrns      JSON NULL,
                           first_seen       DATETIME(3) NULL,
                           last_seen        DATETIME(3) NULL,
                           probable_cause   VARCHAR(500) NULL,
                           runbook_ref      VARCHAR(100) NULL,
                           created_by       VARCHAR(50) NOT NULL DEFAULT 'upi-ops-agent',
                           created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- hard dedup: one OPEN/IN_PROGRESS incident per signature (NULLs ignored by unique index)
                           active_signature VARCHAR(255)
                               GENERATED ALWAYS AS (IF(status IN ('OPEN','IN_PROGRESS'), error_signature, NULL)) STORED,
                           UNIQUE KEY uq_active_sig (active_signature)
) AUTO_INCREMENT = 10001;

CREATE VIEW v_incidents AS
SELECT CONCAT('INC-', LPAD(id,5,'0')) AS incident_no, id, title, category, severity, status,
       error_signature, affected_count, sample_rrns, first_seen, last_seen,
       probable_cause, runbook_ref, created_by, created_at
FROM incidents;

-- ---------------------------------------------------------------
-- 4. Pre-existing OPEN incident (tests dedup: next CBS timeout must update, not duplicate)
-- ---------------------------------------------------------------
INSERT INTO incidents
(title, category, severity, status, error_signature, affected_count, sample_rrns,
 first_seen, last_seen, probable_cause, runbook_ref, created_by, created_at)
VALUES
    ('UPI debit failures: CBS response timeouts on cbs-prod-02','TECHNICAL_CBS','HIGH','OPEN',
     'upi-switch|CBS_DEBIT|91|CBS response timeout after #ms', 12,
     JSON_ARRAY('627810421733','627810421801','627810422054'),
     NOW(3) - INTERVAL 35 MINUTE, NOW(3) - INTERVAL 28 MINUTE,
     'CBS host cbs-prod-02 slow responding; possible DB lock or batch job overlap',
     'RB-CBS-017','ops.user', NOW(3) - INTERVAL 27 MINUTE);

-- ---------------------------------------------------------------
-- 5. Historical error events (42 rows; relative to NOW()). Use as replay material.
-- ---------------------------------------------------------------
INSERT INTO upi_error_logs
(log_ts, level, component, txn_id, rrn, stage, resp_code, reason, reason_norm, payer_vpa, payee_vpa, amount, extra_json) VALUES
                                                                                                                             (NOW(3) - INTERVAL 240 SECOND,'ERROR','upi-switch','UPI20261005104217A91F','627810421733','CBS_DEBIT','91','CBS response timeout after 30000ms','','ra****@abcbank','sh****@okhdfc',2500.00,JSON_OBJECT('cbsHost','cbs-prod-02','retryCount',0,'status','DEEMED')),
                                                                                                                             (NOW(3) - INTERVAL 180 SECOND,'INFO','upi-switch','UPI20261005104302C4D2','627810430212','CBS_DEBIT','51','Insufficient funds','','mk****@abcbank','bigbazaar@icici',18400.00,JSON_OBJECT('availBal',3120.55,'acctType','SAVINGS')),
                                                                                                                             (NOW(3) - INTERVAL 125 SECOND,'ERROR','npci-gateway','UPI20261005104544E7B3','627810454401','NPCI_FORWARD','U30','java.net.SocketTimeoutException: Read timed out','',NULL,NULL,NULL,JSON_OBJECT('npciEndpoint','upi-npci-prod-mum','latencyMs',60012)),
                                                                                                                             (NOW(3) - INTERVAL 120 SECOND,'ERROR','npci-gateway',NULL,NULL,'NPCI_FORWARD','U30','Connection pool exhausted active=200/200 waiting=143','',NULL,NULL,NULL,JSON_OBJECT('pool','npci-conn-pool')),
                                                                                                                             (NOW(3) - INTERVAL 110 SECOND,'ERROR','npci-gateway','UPI20261005104545F1A8','627810454502','NPCI_FORWARD','U30','Unable to acquire connection from pool within 5000ms','',NULL,NULL,NULL,NULL),
                                                                                                                             (NOW(3) - INTERVAL 108 SECOND,'ERROR','npci-gateway','UPI20261005104545B2C9','627810454503','NPCI_FORWARD','U30','Unable to acquire connection from pool within 5000ms','',NULL,NULL,NULL,NULL);

INSERT INTO upi_error_logs
(log_ts, level, component, txn_id, rrn, stage, resp_code, reason, reason_norm, payer_vpa, payee_vpa, amount, extra_json) VALUES
-- ===== A. Invalid MPIN (customer error, WARN, 5 rows) =====
(NOW(3) - INTERVAL 3300 SECOND,'WARN','upi-switch','UPI20261005E001','627811000001','REMITTER_AUTH','ZM','Invalid MPIN, attempts remaining 2','Invalid MPIN, attempts remaining #','ra****@abcbank','amazon@apl',1299.00,JSON_OBJECT('channel','ANDROID')),
(NOW(3) - INTERVAL 2700 SECOND,'WARN','upi-switch','UPI20261005E002','627811000002','REMITTER_AUTH','ZM','Invalid MPIN, attempts remaining 1','Invalid MPIN, attempts remaining #','pk****@abcbank','swiggy@icici',450.00,JSON_OBJECT('channel','IOS')),
(NOW(3) - INTERVAL 2100 SECOND,'WARN','upi-switch','UPI20261005E003','627811000003','REMITTER_AUTH','ZM','Invalid MPIN, attempts remaining 2','Invalid MPIN, attempts remaining #','dv****@abcbank','sh****@okhdfc',5000.00,JSON_OBJECT('channel','ANDROID')),
(NOW(3) - INTERVAL 900 SECOND,'WARN','upi-switch','UPI20261005E004','627811000004','REMITTER_AUTH','ZM','Invalid MPIN, attempts remaining 2','Invalid MPIN, attempts remaining #','sn****@abcbank','zomato@hdfcbank',620.00,JSON_OBJECT('channel','ANDROID')),
(NOW(3) - INTERVAL 400 SECOND,'WARN','upi-switch','UPI20261005E005','627811000005','REMITTER_AUTH','ZM','Invalid MPIN, attempts remaining 1','Invalid MPIN, attempts remaining #','mk****@abcbank','pt****@ybl',2100.00,JSON_OBJECT('channel','IOS')),

-- ===== B. Risk / limit threshold exceeded (policy decline, WARN, 3 rows) =====
(NOW(3) - INTERVAL 3000 SECOND,'WARN','upi-switch','UPI20261005E006','627811000006','RISK_CHECK','U16','Risk threshold exceeded: daily limit 100000','Risk threshold exceeded: daily limit #','ra****@abcbank','jewels@icici',75000.00,JSON_OBJECT('dailyUsed',68000)),
(NOW(3) - INTERVAL 1500 SECOND,'WARN','upi-switch','UPI20261005E007','627811000007','RISK_CHECK','U16','Risk threshold exceeded: daily limit 100000','Risk threshold exceeded: daily limit #','sn****@abcbank','cars24@hdfcbank',100000.00,JSON_OBJECT('dailyUsed',20000)),
(NOW(3) - INTERVAL 600 SECOND,'WARN','upi-switch','UPI20261005E008','627811000008','RISK_CHECK','U16','Risk threshold exceeded: daily limit 100000','Risk threshold exceeded: daily limit #','dv****@abcbank','ap****@oksbi',50000.00,JSON_OBJECT('dailyUsed',90000)),

-- ===== C. Invalid payee VPA (beneficiary-side input error, INFO, 4 rows) =====
(NOW(3) - INTERVAL 3400 SECOND,'INFO','upi-switch','UPI20261005E009','627811000009','VPA_RESOLVE','ZH','Invalid VPA: payee address not found','Invalid VPA: payee address not found','pk****@abcbank','ra****@okxyz',800.00,NULL),
(NOW(3) - INTERVAL 2400 SECOND,'INFO','upi-switch','UPI20261005E010','627811000010','VPA_RESOLVE','ZH','Invalid VPA: payee address not found','Invalid VPA: payee address not found','mk****@abcbank','9876****@paytm',1500.00,NULL),
(NOW(3) - INTERVAL 1200 SECOND,'INFO','upi-switch','UPI20261005E011','627811000011','VPA_RESOLVE','ZH','Invalid VPA: payee address not found','Invalid VPA: payee address not found','ra****@abcbank','sh****@oksb',300.00,NULL),
(NOW(3) - INTERVAL 300 SECOND,'INFO','upi-switch','UPI20261005E012','627811000012','VPA_RESOLVE','ZH','Invalid VPA: payee address not found','Invalid VPA: payee address not found','dv****@abcbank','me****@ybll',2250.00,NULL),

-- ===== D. Beneficiary bank not responding (mostly YESB, ERROR, 6 rows) =====
(NOW(3) - INTERVAL 1500 SECOND,'ERROR','upi-switch','UPI20261005E013','627811000013','BENEFICIARY_CREDIT','91','Beneficiary bank not responding within 15000ms','Beneficiary bank not responding within #ms','ra****@abcbank','sh****@yesbank',3200.00,JSON_OBJECT('beneficiaryBank','YESB','status','DEEMED')),
(NOW(3) - INTERVAL 1450 SECOND,'ERROR','upi-switch','UPI20261005E014','627811000014','BENEFICIARY_CREDIT','91','Beneficiary bank not responding within 15000ms','Beneficiary bank not responding within #ms','mk****@abcbank','vi****@yesbank',12000.00,JSON_OBJECT('beneficiaryBank','YESB','status','DEEMED')),
(NOW(3) - INTERVAL 1380 SECOND,'ERROR','upi-switch','UPI20261005E015','627811000015','BENEFICIARY_CREDIT','91','Beneficiary bank not responding within 15000ms','Beneficiary bank not responding within #ms','pk****@abcbank','an****@yesbank',950.00,JSON_OBJECT('beneficiaryBank','YESB','status','DEEMED')),
(NOW(3) - INTERVAL 1320 SECOND,'ERROR','upi-switch','UPI20261005E016','627811000016','BENEFICIARY_CREDIT','91','Beneficiary bank not responding within 15000ms','Beneficiary bank not responding within #ms','sn****@abcbank','ku****@yesbank',4400.00,JSON_OBJECT('beneficiaryBank','YESB','status','DEEMED')),
(NOW(3) - INTERVAL 1260 SECOND,'ERROR','upi-switch','UPI20261005E017','627811000017','BENEFICIARY_CREDIT','91','Beneficiary bank not responding within 15000ms','Beneficiary bank not responding within #ms','dv****@abcbank','ri****@yesbank',700.00,JSON_OBJECT('beneficiaryBank','YESB','status','DEEMED')),
(NOW(3) - INTERVAL 200 SECOND,'ERROR','upi-switch','UPI20261005E018','627811000018','BENEFICIARY_CREDIT','91','Beneficiary bank not responding within 15000ms','Beneficiary bank not responding within #ms','ra****@abcbank','pr****@axisbank',1800.00,JSON_OBJECT('beneficiaryBank','UTIB','status','DEEMED')),

-- ===== E. HSM connectivity failure during PIN verification (infra, ERROR, 5 rows) =====
(NOW(3) - INTERVAL 1000 SECOND,'ERROR','upi-switch','UPI20261005E019','627811000019','HSM_VERIFY','96','HSM connection refused host=hsm-02 port=1500','HSM connection refused host=hsm-# port=#','mk****@abcbank','amazon@apl',2999.00,JSON_OBJECT('hsmHost','hsm-02')),
(NOW(3) - INTERVAL 960 SECOND,'ERROR','upi-switch','UPI20261005E020','627811000020','HSM_VERIFY','96','HSM connection refused host=hsm-02 port=1500','HSM connection refused host=hsm-# port=#','sn****@abcbank','bigbazaar@icici',860.00,JSON_OBJECT('hsmHost','hsm-02')),
(NOW(3) - INTERVAL 930 SECOND,'ERROR','upi-switch','UPI20261005E021','627811000021','HSM_VERIFY','96','HSM connection refused host=hsm-02 port=1500','HSM connection refused host=hsm-# port=#','pk****@abcbank','zomato@hdfcbank',415.00,JSON_OBJECT('hsmHost','hsm-02')),
(NOW(3) - INTERVAL 880 SECOND,'ERROR','upi-switch','UPI20261005E022','627811000022','HSM_VERIFY','96','HSM connection refused host=hsm-02 port=1500','HSM connection refused host=hsm-# port=#','ra****@abcbank','sh****@okhdfc',15000.00,JSON_OBJECT('hsmHost','hsm-02')),
(NOW(3) - INTERVAL 850 SECOND,'ERROR','upi-switch','UPI20261005E023','627811000023','HSM_VERIFY','96','HSM connection refused host=hsm-02 port=1500','HSM connection refused host=hsm-# port=#','dv****@abcbank','swiggy@icici',530.00,JSON_OBJECT('hsmHost','hsm-02')),

-- ===== F. Auto-reversal failures: customer debited, not reversed (CRITICAL-worthy, ERROR, 4 rows) =====
(NOW(3) - INTERVAL 1780 SECOND,'ERROR','upi-switch','UPI20261005E024','627811000024','CBS_REVERSAL','91','Auto-reversal credit timeout after 30000ms','Auto-reversal credit timeout after #ms','ra****@abcbank','sh****@okhdfc',6500.00,JSON_OBJECT('cbsHost','cbs-prod-02','status','DEBIT_NOT_REVERSED','origRrn','627810421733')),
(NOW(3) - INTERVAL 1760 SECOND,'ERROR','upi-switch','UPI20261005E025','627811000025','CBS_REVERSAL','91','Auto-reversal credit timeout after 30000ms','Auto-reversal credit timeout after #ms','mk****@abcbank','pt****@ybl',2800.00,JSON_OBJECT('cbsHost','cbs-prod-02','status','DEBIT_NOT_REVERSED')),
(NOW(3) - INTERVAL 1740 SECOND,'ERROR','upi-switch','UPI20261005E026','627811000026','CBS_REVERSAL','91','Auto-reversal credit timeout after 30000ms','Auto-reversal credit timeout after #ms','sn****@abcbank','amazon@apl',9990.00,JSON_OBJECT('cbsHost','cbs-prod-02','status','DEBIT_NOT_REVERSED')),
(NOW(3) - INTERVAL 1700 SECOND,'ERROR','upi-switch','UPI20261005E027','627811000027','CBS_REVERSAL','91','Auto-reversal credit timeout after 30000ms','Auto-reversal credit timeout after #ms','pk****@abcbank','zomato@hdfcbank',1100.00,JSON_OBJECT('cbsHost','cbs-prod-02','status','DEBIT_NOT_REVERSED')),

-- ===== G. Collect request expired (customer-side, WARN, 3 rows) =====
(NOW(3) - INTERVAL 2900 SECOND,'WARN','upi-switch','UPI20261005E028','627811000028','COLLECT_EXPIRY','U19','Collect request expired after 1800s','Collect request expired after #s','dv****@abcbank','electricity@bbps',2400.00,NULL),
(NOW(3) - INTERVAL 1800 SECOND,'WARN','upi-switch','UPI20261005E029','627811000029','COLLECT_EXPIRY','U19','Collect request expired after 1800s','Collect request expired after #s','ra****@abcbank','gym@ybl',1500.00,NULL),
(NOW(3) - INTERVAL 700 SECOND,'WARN','upi-switch','UPI20261005E030','627811000030','COLLECT_EXPIRY','U19','Collect request expired after 1800s','Collect request expired after #s','mk****@abcbank','dth@paytm',599.00,NULL),

-- ===== H. Latest minutes: problems still ongoing (6 rows) =====
(NOW(3) - INTERVAL 150 SECOND,'INFO','upi-switch','UPI20261005E031','627811000031','CBS_DEBIT','51','Insufficient funds','Insufficient funds','pk****@abcbank','swiggy@icici',2200.00,JSON_OBJECT('acctType','SAVINGS','availBal',410.20)),
(NOW(3) - INTERVAL 80 SECOND,'INFO','upi-switch','UPI20261005E032','627811000032','CBS_DEBIT','51','Insufficient funds','Insufficient funds','sn****@abcbank','amazon@apl',7800.00,JSON_OBJECT('acctType','CURRENT','availBal',1250.00)),
(NOW(3) - INTERVAL 60 SECOND,'ERROR','upi-switch','UPI20261005E033','627811000033','CBS_DEBIT','91','CBS response timeout after 30000ms','CBS response timeout after #ms','dv****@abcbank','sh****@okhdfc',3300.00,JSON_OBJECT('cbsHost','cbs-prod-02','retryCount',0,'status','DEEMED')),
(NOW(3) - INTERVAL 30 SECOND,'ERROR','upi-switch','UPI20261005E034','627811000034','CBS_DEBIT','91','CBS response timeout after 30000ms','CBS response timeout after #ms','ra****@abcbank','pt****@ybl',990.00,JSON_OBJECT('cbsHost','cbs-prod-02','retryCount',0,'status','DEEMED')),
(NOW(3) - INTERVAL 90 SECOND,'ERROR','npci-gateway','UPI20261005E035','627811000035','NPCI_FORWARD','U30','Unable to acquire connection from pool within 5000ms','Unable to acquire connection from pool within #ms',NULL,NULL,NULL,JSON_OBJECT('npciEndpoint','upi-npci-prod-mum')),
(NOW(3) - INTERVAL 45 SECOND,'ERROR','npci-gateway','UPI20261005E036','627811000036','NPCI_FORWARD','U30','Unable to acquire connection from pool within 5000ms','Unable to acquire connection from pool within #ms',NULL,NULL,NULL,JSON_OBJECT('npciEndpoint','upi-npci-prod-mum'));

-- ---------------------------------------------------------------
-- 6. Least-privilege user for the application (optional)
-- ---------------------------------------------------------------
-- CREATE USER 'upi_agent'@'%' IDENTIFIED BY 'change_me';
-- GRANT SELECT ON abcbank_upi.upi_response_rules TO 'upi_agent'@'%';
-- GRANT SELECT, INSERT, UPDATE ON abcbank_upi.upi_error_logs TO 'upi_agent'@'%';
-- GRANT SELECT, INSERT, UPDATE ON abcbank_upi.incidents TO 'upi_agent'@'%';
-- GRANT SELECT ON abcbank_upi.v_incidents TO 'upi_agent'@'%';

-- Sanity checks
-- SELECT COUNT(*) FROM upi_error_logs;            -- expect 42
-- SELECT * FROM upi_response_rules;               -- expect 11
-- SELECT * FROM v_incidents;                      -- expect INC-10001 (OPEN)
-- SELECT error_signature, reason_norm FROM upi_error_logs LIMIT 5;