# UT-1 — User testing protocol

*Written for whoever facilitates the sessions. Everything needed to run them is
in this directory: the task scripts per role, the observation sheet, the System
Usability Scale questionnaire and its scoring, and the results template that the
sessions fill in.*

**Status: the materials are ready; no session has been run yet.** The results
file ([`ut-1/results.md`](ut-1/results.md)) is a template with every table
empty, and it says so at the top. Nothing in this repository reports a usability
finding or a SUS score until real people have sat down with the platform. See
§7 for what the facilitator does and what comes back into the repository
afterwards.

## 1. What this measures

Whether a person who has never seen Nidaa can, unaided:

- ask for material help, including on behalf of someone else;
- ask for psychological support, anonymously if they choose;
- find the request they should respond to and take it through to completion;
- record a support session and understand what the person on the other side
  will and will not see.

The measures are **task completion** (unaided / with a hint / failed), **time on
task**, **observed hesitations, misreadings and errors**, and one **SUS score**
per participant, so the whole system has a single comparable number before and
after the fixes.

## 2. Participants

Five to ten people, which is the usual size for this kind of study: five
participants surface most of the problems a design has, and the sixth onward
mostly repeat them. Classmates role-playing the three roles is normal and
expected for a graduation project; say so in the write-up rather than implying
they were real beneficiaries.

Suggested split for six people, two per role:

| # | Role played | Scripts |
|---|---|---|
| P1, P2 | Beneficiary | [`tasks-beneficiary.md`](ut-1/tasks-beneficiary.md) |
| P3, P4 | Volunteer | [`tasks-volunteer.md`](ut-1/tasks-volunteer.md) |
| P5, P6 | Psychologist | [`tasks-psychologist.md`](ut-1/tasks-psychologist.md) |

Record only what the study needs: a participant number, the role played, whether
they had seen the platform before, and how comfortable they are with web
applications generally (a one-to-five self-rating). No names, no contact
details, nothing that identifies the person in the write-up. Tell each
participant, before starting, that the software is being tested and not them,
that they may stop at any time, and that nothing they type is kept beyond these
notes — the sessions run against a throwaway database that is dropped
afterwards.

## 3. Setup

One facilitator machine runs the application and the participant uses it. Each
session starts from the same state, which
[`scripts/ut/setup-participants.sh`](../../scripts/ut/setup-participants.sh)
creates: a fresh database, the six accounts above (already verified and
approved, so nobody spends their session waiting for an e-mail), and a starting
queue of pending requests across three settlements so the volunteer scripts have
something to find.

```bash
# 1. a database for the session, from the migrations
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe" scripts/gate/fresh-db.sh nidaa_ut

# 2. a mail sink, so registration works without a real mail server
python scripts/gate/smtp-sink.py 1025

# 3. the application against that database, with a wider auth budget: the setup
#    script below makes a dozen authentication calls in a few seconds
DB_URL="jdbc:postgresql://localhost:5432/nidaa_ut?stringtype=unspecified" \
RATELIMIT_AUTH_PER_MINUTE=50 \
MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAIL_SSL=false MAIL_AUTH=false \
./mvnw spring-boot:run

# 4. the participants and the starting queue
BASE=http://127.0.0.1:8081 DB=nidaa_ut scripts/ut/setup-participants.sh
```

Re-run steps 1 and 4 between participants so that each one meets the same queue.
Passwords and e-mail addresses are printed by the script; they are throwaway
credentials for a throwaway database and belong on the facilitator's sheet, not
in this file.

## 4. Running a session

About 30 minutes per participant.

1. **Brief (2 min).** Explain the scenario for the role, the consent points in
   §2, and that you will not help. Ask them to think aloud: "say what you are
   looking for and what you expect to happen".
2. **One warm-up task**, marked as such in each script, to get them talking.
3. **The tasks in order.** Start the timer when they finish reading the task and
   stop it when they say they are done — not when the screen changes, because a
   participant who does not realise they have succeeded has not succeeded.
4. **Observe without helping.** When they are stuck, wait. After a full minute of
   being stuck, give the smallest hint that unblocks them and record the task as
   *completed with a hint*. If a hint does not help within another minute, move
   on and record *failed*.
5. **SUS (3 min)** at the end, on the whole platform, before any discussion of
   what went wrong — discussion first would change the answers.
6. **Two closing questions.** "What was the most confusing moment?" and "What
   would you tell someone about to use this?" Write the answers down verbatim;
   the quotes are what make the write-up concrete.

Never explain a screen before they have tried it, never confirm that what they
did was right, and never defend the design during the session.

## 5. What to write down

One [`observation-sheet.md`](ut-1/observation-sheet.md) per participant, filled
in during the session. Per task: outcome, time, the hesitations and wrong turns
in the order they happened, and anything the participant said that explains
*why*. Note the misreadings especially — a participant who reads "Sessions" as
"chat" has found a naming problem, even if they complete the task.

## 6. Analysis

1. Pool the problems from all sheets and group them by what caused them, not by
   where they appeared.
2. Rate each group by **severity** (1 irritation → 4 blocks the task) and how
   many participants hit it.
3. Rank by severity first, count second. The top three are the ones that get
   fixed.
4. Score SUS per participant (instructions in
   [`sus-questionnaire.md`](ut-1/sus-questionnaire.md)) and report the mean, the
   standard deviation and every individual score. With six participants the mean
   is indicative, not precise; say so.

## 7. Fixing and re-testing

The plan asks for the top three findings to be fixed and re-tested with two or
three people, which is what turns the study into a before/after number.

- Each fix is its own commit with `Audit-Ref: UT-1` and a line in `results.md`
  saying which finding it addresses.
- The re-test uses **new participants** (someone who has already failed a task
  cannot fail it freshly) and runs only the tasks the three findings affected,
  plus the SUS again.
- `results.md` then holds both rounds: the finding, the change, and whether the
  second round still hit it.

Anything found but not fixed goes into `FUTURE_WORK.md` with its severity, so
"not done" reads as "known and deferred".

## 8. Files

| File | What it is |
|---|---|
| [`ut-1/tasks-beneficiary.md`](ut-1/tasks-beneficiary.md) | Five tasks: ask for food, ask for support anonymously, follow a request, rate a session, cancel |
| [`ut-1/tasks-volunteer.md`](ut-1/tasks-volunteer.md) | Five tasks: find the most urgent nearby request, accept it, say you are on the way, complete it with a report, file on someone's behalf |
| [`ut-1/tasks-psychologist.md`](ut-1/tasks-psychologist.md) | Five tasks: go on duty, accept a case, record a session with a private note, check what the person sees, close the case |
| [`ut-1/observation-sheet.md`](ut-1/observation-sheet.md) | One per participant; printed or copied per session |
| [`ut-1/sus-questionnaire.md`](ut-1/sus-questionnaire.md) | The ten standard items, the scoring rule and a worked example |
| [`ut-1/results.md`](ut-1/results.md) | The template the sessions fill in — empty until they are run |
| [`scripts/ut/setup-participants.sh`](../../scripts/ut/setup-participants.sh) | Accounts and the starting queue for a session database |
