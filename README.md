# UPI Error Classifier (ABC Bank Demo)

A Spring AI agent that receives UPI transaction error logs over REST, classifies each one by category and severity, and decides whether an incident is needed.

**Current stage:** classification is working. Incident creation (second tool) is the next step.

## How it works

```
POST /api/errors  (one UPI error event)
        |
        v
UpiErrorController
        |
        v
UpiErrorClassificationService
  - ChatClient + system prompt
  - tool: classify_error
        |
        v
ClassificationTools.classify_error(respCode, stage)
  - reads upi_response_rules (MySQL, via JdbcClient)
        |
        v
Structured JSON response: ClassificationResult
```

1. The controller accepts a single error event.
2. The service sends it to the LLM with a system prompt and the `classify_error` tool.
3. The model calls the tool, which looks up the bank's rule for the (response code, stage) pair in MySQL.
4. The model returns a typed result: category, severity, `createIncident`, confidence, probable cause, runbook reference and reasoning.

Severity comes from the database rule, so results are repeatable. The model may raise it by one level when the event shows wider impact, and it judges from the reason text only when no rule matches (confidence is then 0.6 or lower).

## Tech stack

- Java, Maven
- Spring Boot 4.1.x, Spring AI 2.0.x
- MySQL 8.0.16+
- Spring AI MCP client (connected to a MySQL MCP server, used for the upcoming incident step)

## Prerequisites

- JDK and Maven installed
- MySQL 8 running locally
- An API key for the chat model configured in your Spring AI setup
- The MySQL MCP server configured in `mcp-servers.json` / `application.yml` (already working in your setup)

## Database setup

Run the schema script once. It starts with `DROP DATABASE IF EXISTS abcbank_upi`, so it deletes all previous data.

```bash
mysql -u <user> -p < abcbank_upi_full.sql
```

It creates:

| Object | Purpose |
|---|---|
| `upi_response_rules` | Classification rules per (response code, stage): category, default severity, incident required, runbook |
| `upi_error_logs` | Audit table for incoming events (not yet written to by the app) |
| `incidents` | Incident tickets, with a unique index of one open incident per error signature |
| `v_incidents` | Incidents with `INC-xxxxx` numbering |

It also seeds one open incident, `INC-10001`, for the CBS timeout signature. It is used to test duplicate handling in the next stage.

The 42 historical log rows in the script are optional. Events come from the REST API, so you can remove them.

## Configuration

`application.yml` needs a datasource in addition to your existing AI and MCP settings:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/abcbank_upi
    username: <your_user>
    password: <your_password>
```

Required Maven dependencies (besides Spring AI):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

## Run

```bash
mvn spring-boot:run
```

The app listens on `http://localhost:8080`. Disable any old `CommandLineRunner` demo bean so it doesn't call the LLM on every startup.

## API

### `POST /api/errors`

Request body:

| Field | Type | Required | Example |
|---|---|---|---|
| `timestamp` | ISO datetime | no | `2026-10-05T10:42:17.381` |
| `component` | string | no | `upi-switch`, `npci-gateway` |
| `stage` | string | yes | `CBS_DEBIT`, `NPCI_FORWARD` |
| `respCode` | string | yes | `91`, `51`, `U30` |
| `reason` | string | yes | `CBS response timeout after 30000ms` |
| `rrn` | string | no | `627810421733` |
| `txnId` | string | no | `UPI20261005104217A91F` |
| `amount` | number | no | `2500.00` |
| `extra` | object | no | `{"cbsHost":"cbs-prod-02"}` |

Missing `respCode`, `stage` or `reason` returns `400`.

Response body:

```json
{
  "category": "TECHNICAL_CBS",
  "severity": "HIGH",
  "createIncident": true,
  "confidence": 0.95,
  "probableCause": "CBS host not responding within timeout",
  "runbookRef": "RB-CBS-017",
  "reasoning": "Matched rule for response code 91 at CBS_DEBIT."
}
```

The exact wording of `probableCause` and `reasoning` varies, because the model writes it.

## Test

Run `test-events.sh` in Git Bash or WSL. The single-quoted JSON does not work in `cmd` or PowerShell. It sends three events:

| Event | Expected severity | `createIncident` |
|---|---|---|
| CBS timeout (91, `CBS_DEBIT`) | HIGH | true |
| Insufficient funds (51, `CBS_DEBIT`) | LOW | false |
| NPCI pool failure (U30, `NPCI_FORWARD`) | CRITICAL | true |

In Postman, use `POST http://localhost:8080/api/errors` with a raw JSON body. Type the URL by hand, because a pasted trailing newline causes a `404`.

## Project structure

```
src/main/java/org/example/springai_mcp_client_mysql/
  web/UpiErrorController.java                    REST endpoint
  service/UpiErrorClassificationService.java     ChatClient, system prompt, structured output
  tools/ClassificationTools.java                 @Tool classify_error (JDBC rule lookup)
  model/UpiErrorEvent.java                       request payload
  model/ClassificationResult.java                response (category, severity, ...)
  model/RuleMatch.java                           tool result returned to the model
```

## Classification rules (seeded)

| Code | Stage | Category | Severity | Incident |
|---|---|---|---|---|
| 51 | CBS_DEBIT | CUSTOMER_DECLINE | LOW | no |
| ZM | REMITTER_AUTH | CUSTOMER_DECLINE | LOW | no |
| U16 | RISK_CHECK | CUSTOMER_DECLINE | LOW | no |
| ZH | VPA_RESOLVE | BENEFICIARY_DECLINE | LOW | no |
| U19 | COLLECT_EXPIRY | CUSTOMER_DECLINE | LOW | no |
| 91 | BENEFICIARY_CREDIT | BENEFICIARY_DECLINE | MEDIUM | yes |
| 91 | CBS_DEBIT | TECHNICAL_CBS | HIGH | yes |
| 96 | CBS_DEBIT | TECHNICAL_CBS | HIGH | yes |
| 96 | HSM_VERIFY | INFRA_HSM | HIGH | yes |
| 91 | CBS_REVERSAL | TECHNICAL_CBS | CRITICAL | yes |
| U30 | NPCI_FORWARD | INFRA_NPCI | CRITICAL | yes |

The response codes are illustrative. Align them with your own NPCI response-code catalogue before a real deployment. To change behavior, edit the rows in `upi_response_rules`; no code change is needed.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `404` on `/api/errors%0A%20` | A newline was pasted into the URL. Retype it. |
| `package org.springframework.jdbc.core.simple does not exist` | Add `spring-boot-starter-jdbc` and reload Maven. |
| App fails at startup with "Client failed to initialize" / 20s timeout | The MCP server process did not respond. Run its command by hand, check credentials and stdout cleanliness, and consider a longer `request-timeout`. |
| Datasource errors at startup | Check the `spring.datasource.*` settings and that the `abcbank_upi` database exists. |
| `matched=false` in logs for a known error | The (respCode, stage) pair is not in `upi_response_rules`. Check spelling and case. |

## Roadmap

1. `create_incident` tool: severity gate (MEDIUM and above), duplicate check against open incidents, insert or update `affected_count`, `last_seen` and sample RRNs.
2. Write each event and its classification to `upi_error_logs`.
3. RAG over runbooks and past incidents for richer probable-cause text.
4. Human approval step before ticket creation.
5. Integration with a real ticketing system (ServiceNow, Jira) through MCP.# mcp_localllm_qwen_springai_bank
