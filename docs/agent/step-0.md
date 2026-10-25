STEP 0 — ALIGNMENT. Do not write any code.

Branch: create agent/step-0 from origin/main.

1. Read README.md, the engine-test/ app, /workspace/ref/builder-guide.txt, and the
   files in /workspace/ref/skills/.
2. Search /workspace/ref/ideation-transcript.jsonl only for specific context you
   need (jq or rg on the "type" and "content" fields). Do not read it end to end.
3. Write docs/agent/STEP0_PLAN.md with these sections:
   A. Questions for the human (at most 10, most important first). For each, state
      the default you will use until the human answers.
   B. Risks and unknowns, ranked, each with how to retire it cheaply.
   C. Module plan for Steps 1–4: packages, public interfaces (Kotlin signatures),
      the build setup (Gradle version, JDK, Kotlin version) that works in this
      sandbox, and the exact pass/fail command for each step.
   D. Disagreements with LONG_TERM_PLAN.md, if any, with reasons.
   E. Estimated size of each step (files, lines, tests).
   Keep it under 2,500 words.
4. Start docs/agent/DECISIONS.md with the defaults from section A.
5. Commit, push agent/step-0, and open a draft PR into main titled
   "Agent step 0: alignment plan".

Reply with a 5-line summary and the PR URL.
