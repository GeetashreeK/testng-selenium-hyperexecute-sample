# HyperExecute Assignment — Notes

Every fix below was verified rather than assumed: the broken YAML was run through a real parser
(`js-yaml`) to confirm each error, and the configs were run as real jobs on HyperExecute.

---

## Task 1 — Fix the broken YAML

**Broken file** (from the gist, `(TestNG)Fixme.yaml`):

```yaml
---
version: 0.1
runson: win

autosplit: true
conCurrency: 1

env: TOKEN: anvdegtod-asdaasda0asda-asda

pre:
  - mvn dependency:resolve

testDiscovery:
  type: raw
  mode: dynamic
  command: grep 'test name' xml/testng_win.xml | awk '{print$2}' | sed 's/name=//g' | sed 's/\x3e//g'

testRunnerCommand: mvn test `-Dplatname=win `-Dmaven.repo.local=./.m2 dependency:resolve `-DselectedTests=$test

 retryOnFailure: true
maxRetries: 1

jobLabel: [selenium-testng, win, v1, autosplit]
```

**Fixed file:** [`yaml/win/v1/testng_hyperexecute_fixed.yaml`](yaml/win/v1/testng_hyperexecute_fixed.yaml)

### Errors found, and why each one breaks the job

- **`env: TOKEN: ...` — invalid YAML, kills the whole file.** Two colons on one line is not a
  nested mapping; the parser rejects it outright with
  `YAMLException: bad indentation of a mapping entry (8:11)`. HyperExecute can't parse the file,
  so no job is created at all. **Fix:** nest it under `env:`.

- **Stray leading space before `retryOnFailure: true` — a second, independent fatal parse error.**
  Every other top-level key starts at column 0; this one has a single leading space, which YAML
  reads as trying to nest it under `testRunnerCommand` (which already has a scalar value).
  Confirmed by fixing *only* the `env:` bug and re-parsing — it then fails here instead:
  `bad indentation of a mapping entry (21:16)`. Easy to miss if you stop at the first error.
  **Fix:** remove the leading space.

- **`conCurrency: 1` — wrong casing.** The key is `concurrency`. An unrecognised key is silently
  ignored, so the job runs at default concurrency instead of the intended value — a silent
  misconfiguration rather than a crash. **Fix:** `concurrency: 4`.

- **`mode: dynamic` — deprecated.** LambdaTest's YAML docs list only `local` and `remote`, and
  state the earlier `dynamic` mode has been deprecated. This repo's own commit
  `3808192 [TE-15345] dynamic to remote mode (#60)` made the same rename across its samples.
  **Fix:** `mode: remote`.

- **`pre` step doesn't match the cache path the tests use.** `mvn dependency:resolve` resolves
  into Maven's default local repo, but `testRunnerCommand` runs with `-Dmaven.repo.local=./.m2`
  — two different directories, so the pre-step warms a cache the test run never reads.
  **Fix:** `mvn -Dmaven.repo.local=./.m2 dependency:resolve`.

- **No `cacheKey` / `cacheDirectories` / `runtime` block.** Not fatal, but without it `.m2` isn't
  cached between runs and the Java version isn't pinned, so every job re-downloads all
  dependencies. Added for parity with the repo's other samples.

### One thing I checked and deliberately did *not* "fix"

The backticks in `testRunnerCommand` (``mvn test `-Dplatname=win ...``) look wrong at a glance,
but the same syntax appears in this repo's own working sample
(`yaml/win/v1/testng_hyperexecute_autosplit_sample.yaml`), and the real job resolved
`-Dplatname=win` correctly with them left in place.

### Evidence

Green job: `https://hyperexecute.lambdatest.com/hyperexecute/task?jobId=e1653b6e-0077-41f5-944e-f1c6f374fbf4`

---

## Task 2 — Environment variables

In [`testng_hyperexecute_fixed.yaml`](yaml/win/v1/testng_hyperexecute_fixed.yaml):

```yaml
env:
  TOKEN: anvdegtod-asdaasda0asda-asda
  ENVIRONMENT: staging

pre:
  - mvn -Dmaven.repo.local=./.m2 dependency:resolve
  - echo ENVIRONMENT is %ENVIRONMENT%
```

My first attempt used PowerShell syntax (`$env:ENVIRONMENT`) and it silently didn't work — the
job log printed the literal, unexpanded text. Rather than guess again I ran one job with all
three candidate syntaxes side by side:

```
CMD_STYLE staging          <- echo CMD_STYLE %ENVIRONMENT%
POWERSHELL_STYLE :ENVIRONMENT   <- echo POWERSHELL_STYLE $env:ENVIRONMENT
BASH_STYLE                 <- echo BASH_STYLE $ENVIRONMENT
```

Only `%VAR%` expanded, which shows `pre` steps run through **cmd.exe** on the Windows runners.
(The backticks in `testRunnerCommand` are an unrelated convention.)

Read inside a test — [`src/test/java/Test1.java`](src/test/java/Test1.java), in `@BeforeMethod`:

```java
System.out.println("ENVIRONMENT=" + System.getenv("ENVIRONMENT"));
```

**Confirmed in the logs:** pre-step shows `ENVIRONMENT is staging`; `Test_1`'s console output
shows `ENVIRONMENT=staging` immediately before `Started session`.

---

## Task 3 — Force a failure and configure retries

[`src/test/java/TestIntentionalFailure.java`](src/test/java/TestIntentionalFailure.java):

```java
@Test(description = "Intentional hard failure to validate HyperExecute retryOnFailure")
public void test_intentional_failure() {
    Assert.assertEquals(1, 2, "Intentional failure - used to verify retryOnFailure behavior");
}
```

A hard assertion, so it fails every time with no timing or network dependency. It's a separate
class rather than a method on `Test1` because `Test1`'s `@BeforeMethod` opens a real browser
session before every test — this way each retry fails fast instead of burning a session.

### Why this is a separate job from Task 1

My first attempt put `Test_Fail` into `xml/testng_win.xml`, the same suite Task 1's
`testDiscovery` greps. That made Task 1's deliverable impossible: a failing scenario marks the
**whole job** FAILED, and there is no flag to suppress that (`failFast` and `alwaysRunPostSteps`
exist; neither keeps a job green). Task 1 wants a job that succeeds, Task 3 wants one with a
failing test — one job can't do both. So the failure lives in its own suite and YAML:

- [`xml/testng_win_retry.xml`](xml/testng_win_retry.xml) — only the `Test_Fail` entry
- [`yaml/win/v1/testng_hyperexecute_retry.yaml`](yaml/win/v1/testng_hyperexecute_retry.yaml)

Selected via `-Dplatname=win_retry`, since `pom.xml` resolves its suite as
`xml/testng_${platname}.xml`.

Verified each job discovers exactly the right buckets, using `testDiscovery`'s own pipeline:

```bash
$ grep 'test name' xml/testng_win.xml | awk '{print$2}' | sed 's/name=//g' | sed 's/\x3e//g'
"Test_1"
"Test_2"
"Test_3"
"Test_4"

$ grep 'test name' xml/testng_win_retry.xml | awk '{print$2}' | sed 's/name=//g' | sed 's/\x3e//g'
"Test_Fail"
```

Retry config (`maxRetries` accepts 1–5; 2 makes the sequence unmistakable):

```yaml
retryOnFailure: true
maxRetries: 2
```

Retries fire only when `testRunnerCommand` exits non-zero, and re-run the whole failing task —
so HyperExecute reruns `mvn test -DselectedTests=Test_Fail` rather than retrying the TestNG
method itself.

**Confirmed in the CLI output:**

```
x [4]  "Test_Fail" (11s)
x [4]  {retry 1} "Test_Fail" (5s)
x [4]  {retry 2} "Test_Fail" (6s)
```

The dashboard's Scenario History panel for `Test_Fail` independently lists three failed attempts
with their own timestamps.

---

## Task 4 — Linux/Unix basics

Sample input: [`sample_job.log`](sample_job.log) — 12 space-delimited lines of
`date time LEVEL testname method browser env`.

### 1) `grep` — find every FAIL/ERROR line

```bash
grep -E 'FAIL|ERROR' sample_job.log
```
> Prints only lines matching `FAIL` or `ERROR` (`-E` enables the `|` alternation).

```
2026-08-20 09:00:12 FAIL Test_2 element_addition_1 firefox staging
2026-08-20 09:00:22 ERROR Test_Fail test_intentional_failure none staging
2026-08-20 09:00:25 ERROR Test_Fail test_intentional_failure none staging
```

### 2) `awk` — print just the 2nd column

```bash
awk '{print $2}' sample_job.log
```
> Splits each line on whitespace and prints the second field — here the time.

```
09:00:01
09:00:04
...
09:00:27
```

### 3) `sed` — find-and-replace `staging` → `production`

```bash
sed 's/staging/production/g' sample_job.log
```
> Replaces every occurrence per line (`g` = global). The file itself is untouched without `-i`.

```
2026-08-20 09:00:01 INFO Test_1 started chrome production
...(all 12 lines, staging -> production)
```

### 4) Chained with a pipe — which tests failed

```bash
grep -E 'FAIL|ERROR' sample_job.log | awk '{print $4}'
```
> Filters to failing lines first, then pulls out just the test-name column.

```
Test_2
Test_Fail
Test_Fail
```

---

## Running the jobs

Set `LT_USERNAME` / `LT_ACCESS_KEY` in your terminal first, then:

```powershell
# Tasks 1 & 2 - finishes GREEN
.\hyperexecute.exe --config yaml\win\v1\testng_hyperexecute_fixed.yaml --force-clean-artifacts --download-artifacts

# Task 3 - expected to FAIL, with Test_Fail retried twice
.\hyperexecute.exe --config yaml\win\v1\testng_hyperexecute_retry.yaml --force-clean-artifacts --download-artifacts
```

`.hyperexecuteignore` keeps the submission `.docx`, CLI binaries and build output out of the
uploaded payload — without it, an open Word document locks the file and the CLI's archive step
fails.
