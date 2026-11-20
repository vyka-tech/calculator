# calculator

A basic web calculator on AWS Lambda (Java 17): add, subtract, multiply, divide, power and modulo from a keypad in your browser or a JSON API, with exact decimal maths. It is a deliberately vulnerable demo target for CodeShield AI scanning and fixing.

**Service URL:** <https://f74gaokucajkw5w37gxgxgwihm0qdyof.lambda-url.eu-west-1.on.aws/>

> ⚠️ The dependency versions in `pom.xml` are deliberately outdated and have known CVEs. Do not reuse them.

## What it does

- `GET /` is a keypad calculator. Every `=` is computed by the Lambda, and a table under the keypad shows the
  library versions running in this deployment with a pass/fail self-test for each.
- `GET /api/calc?a=12&b=3&op=divide` returns the result as JSON.
- `GET /api/health` returns `UP` plus every library version and self-test result (HTTP 503 if any fails).

Try it: [12 ÷ 3](https://f74gaokucajkw5w37gxgxgwihm0qdyof.lambda-url.eu-west-1.on.aws/api/calc?a=12&b=3&op=divide) ·
[0.1 + 0.2](https://f74gaokucajkw5w37gxgxgwihm0qdyof.lambda-url.eu-west-1.on.aws/api/calc?a=0.1&b=0.2&op=add) ·
[2 ^ 10](https://f74gaokucajkw5w37gxgxgwihm0qdyof.lambda-url.eu-west-1.on.aws/api/calc?a=2&b=10&op=power) ·
[health](https://f74gaokucajkw5w37gxgxgwihm0qdyof.lambda-url.eu-west-1.on.aws/api/health)

## API

| Route | Returns |
|---|---|
| `GET /` | Keypad calculator in the browser, plus the library versions running in this deployment |
| `GET /api/calc?a=12&b=3&op=divide` | `{"a":"12","b":"3","op":"divide","expression":"12 ÷ 3","result":"4"}` |
| `GET /api/health` | `{"status":"UP","dependencies":[{"library":"jackson-databind","version":"2.13.2","ok":true}, ...]}` (503 if any self-test fails) |

Operations: `add`, `subtract`, `multiply`, `divide`, `power` (whole exponent 0–100), `modulo`. Arithmetic uses
`BigDecimal` with 16 digits of precision, so `0.1 + 0.2 = 0.3`. Invalid input returns HTTP 400 with an error message.

## Vulnerable dependencies

| Dependency | Version | Example CVE | Fixed in |
|---|---|---|---|
| log4j-core / log4j-api | 2.16.0 | CVE-2021-45105, CVE-2021-44832 | 2.17.1 |
| jackson-databind | 2.13.2 | CVE-2020-36518, CVE-2022-42003 | 2.13.4.2 |
| commons-lang3 | 3.12.0 | CVE-2025-48924 | 3.18.0 |
| commons-text | 1.9 | CVE-2022-42889 (Text4Shell) | 1.10.0 |
| commons-io | 2.6 | CVE-2021-29425, CVE-2024-47554 | 2.14.0 |
| snakeyaml | 1.30 | CVE-2022-1471, CVE-2022-25857 | 2.0 |
| guava | 30.1-jre | CVE-2023-2976, CVE-2020-8908 | 32.0.0-jre |

Every library is used by the calculator itself (parsing, escaping, config, JSON, logging), so an upgrade that
breaks something shows up on the page and fails the pipeline's health check. Caller input is validated as a plain
number before use, never logged, never interpolated and never deserialised polymorphically.

## How it fits CodeShield AI

- Deployed as a **ZIP Lambda** (`java17`); `mvn package` builds `target/calculator.zip` (classes at the root,
  jars in `lib/`), which **Amazon Inspector Lambda scanning** reads.
- In CodeShield, register this repo and pick the `calculator` Lambda as its scan target.
- `Dockerfile` is a **build check only**: `docker build .` is how CodeShield validates a fix.

## CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| `CI` | Every PR to `main` (incl. CodeShield fix PRs) | `mvn package` and checks the zip layout |
| `Deploy` | Push to `main`, or manual | Builds, updates the Lambda, checks `6 × 7 = 42`, then `GET /api/health` must be `UP` |

AWS access uses GitHub OIDC (no stored keys). `infra/github-deploy-role.yml` defines a role that only `main` of
this repo can assume, limited to the `calculator` function. One-time setup (done for account `024265813171`):

```powershell
aws cloudformation deploy --stack-name calculator-github-deploy --template-file infra/github-deploy-role.yml --capabilities CAPABILITY_NAMED_IAM --region eu-west-1 --profile yogiltd
```

## Manual deploy

```powershell
.\deploy.ps1 -Profile yogiltd
```
