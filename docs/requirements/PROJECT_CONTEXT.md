# AI Health Assistant project handoff for Astra

Repository update: this document preserves the original requirements-analysis snapshot. The owner has since requested the baseline, the exact six-module split in `docs/MODULES.md`, small synthetic test data, and a private server-side `.env` with an OpenAI-compatible LLM adapter. Follow those newer instructions for repository organization. Statements below about the application not yet existing describe the earlier snapshot, not current scaffold status. The source SRS still controls product requirements.

Prepared on 29 October 2026 for the SE214 Group 5 project. This file consolidates the supplied ideas into working context for design and implementation. Read this first, then consult the original SRS for detailed flows. The product is a Vietnamese health information and lifestyle support web application for adults. Its core is a rule-controlled Safety Gate followed, when appropriate, by answers grounded in approved documents.

**Owner constraint:** development and data collection run on a personal computer with limited compute and storage. Collect a small, curated dataset. Do not assume a GPU, large local model, large crawl, or model training is available.

**Status:** the four source files have been read, including all six workbook sheets and the ten sequence diagram images embedded in the SRS. This handoff does not mean the application, crawler, approved medical content, or evaluation dataset already exists. No web data was collected for this handoff.

## 1. Sources and authority

All four sources are in `D:\Luan\Study\SE214`.

| Reference | File | How to use it |
| --- | --- | --- |
| S1 | `SRS – Trợ lý Sức khỏe AI (Nhóm 5).docx` | Main authority. Includes scope, BR, FR, 19 detailed UC, sequence diagrams, NFR, data constraints, and evaluation. |
| S2 | `Dac_ta_Use_Case_chuan_hoa_19_UC.docx` | Supporting normalized specifications for all 19 UC. Use for compatible flow details. |
| S3 | `UC_FR_Mapping_Nhom5.xlsx` | Traceability and permissions. Sheets: `Huong dan`, `Vai tro`, `Phan quyen`, `Danh sach UC`, `Ma tran UC-FR`, `Metric NFR`. |
| S4 | `AI_Health_Assistant_Data_Plan.md` | Older project summary and timeline context. Several requirements and metrics are superseded by S1. Despite its name, it is not a detailed crawl specification. |

The owner's explicit instructions govern this handoff. For conflicts among the supplied files, follow **S1**. S2 and S3 fill compatible details; S4 is background only. Sections marked **Proposal** below are implementation suggestions, not new SRS requirements. Do not silently convert them into agreed scope.

Useful source locations:

- S1 §1.4–1.5: product boundaries and roles.
- S1 §2.1.2: BR-01–BR-12.
- S1 §2.2, Tables 2.1–2.3: 18 FR, five care levels, and 19 UC.
- S1 §2.2.5: detailed UC flows and embedded sequence diagrams.
- S1 §2.3.1–2.3.9: data, AI flow, integrations, 22 NFR, and constraints.
- S1 Chapter 3, Tables 3.1–3.2 and §3.3: 120 evaluation cases, 12 metrics, and threshold interpretation.
- S3 `Ma tran UC-FR!A3:X20`: the explicit FR-to-UC marks. All 18 FR have at least one marked UC, as checked from the marks rather than relying on cached formula totals.
- S3 `Phan quyen!A1:F15` and `Vai tro!A9:H31`: role permissions and personal-use inheritance.

## 2. Product intent and scope

Vietnamese-speaking adults aged 18 or older should be able to ask general health questions, understand when to seek care, find verified public information, and maintain healthier routines. The project is a student MVP/demo, with no clinical validation claimed. The SRS's customer scenarios are hypothetical; user research has not yet been completed.

The principal journeys are:

1. Register, confirm adulthood, and consent to the stated processing of health data.
2. Ask a question or describe a concern. The system checks approved warning rules first, asks for missing information when needed, and provides a care direction without predicting a disease.
3. Receive a sourced answer from approved material, with an appropriate specialty or a facility from the existing directory when relevant.
4. Optionally maintain a health profile and daily measurements, choose an approved lifestyle plan, track habits, and receive reminders.
5. Optionally rate an AI answer and choose whether to share that one question–answer pair anonymously with a content expert.

### In scope

- Responsive Vietnamese web interface for desktop, tablet, and phone.
- General health information with real citations to approved material.
- Rule-based warning checks and five care-direction levels.
- Personal account, consent, deletion, and chat history management.
- Self-entered height, weight, age, sex, optional food allergies, and daily sleep hours; BMI and simple trends.
- Approved lifestyle plan templates for weight gain, weight loss, maintenance, and better sleep; exclude templates conflicting with declared allergies.
- Public article search and a small preloaded, verified healthcare facility directory.
- Content and rule authoring/review, account administration, and aggregate statistics.

### Outside scope

- Diagnosing or predicting a disease from symptoms.
- Prescriptions, drug suggestions, medication doses, drug lookup, or instructions to change/stop treatment.
- AI-generated therapeutic menus, calorie prescriptions, or personalized exercise programs.
- Under-18 users, voice input, medical image analysis, wearable/device integration.
- Electronic medical records, family medical history, laboratory results.
- Appointment booking, direct doctor consultation, payments, facility ratings or rankings.

`ROUTINE_CONSULT` means advice to arrange a consultation. It does not add appointment-booking functionality. A user-supplied disease name may be used as a search topic; it is not a confirmed diagnosis.

## 3. Roles and privacy boundaries

| Role | Allowed responsibilities | Important limits |
| --- | --- | --- |
| R-01 Khách | Read/search approved articles, search the public facility directory, register. | No personal health features or chatbot access as a guest. |
| R-02 Người dùng | Own account, consent, own health data, chatbot/history, ratings, plans, habits, reminders. | Access only their own private records. |
| R-03 Chuyên gia nội dung | Author and review content, templates and warning rules; approve or return submissions; process anonymous answer feedback. | No access to another person's full chat or health profile. |
| R-04 Quản trị viên | Manage accounts, locks and expert-role assignment; view aggregate usage and restricted operational logs; content management is described broadly in S1. | No private chats/profiles of others; no feedback content; no expert approval permission merely from being admin. See ambiguity A1. |
| System | Execute Safety Gate, scheduling, approved calculations, retrieval and integration logic. | An internal executor, not a fifth human permission role. |

S3 clarifies that R-03 and R-04 can use ordinary personal features on **their own** data, subject to the same adulthood and consent conditions. An admin is created during setup; new public registrations become R-02. Expert status is granted or revoked by an admin.

Privacy rules to preserve in every layer:

- Enforce role and ownership checks at the backend on every request. Hiding UI controls is insufficient.
- Store consent time and the version of the accepted terms. Require consent before health functions.
- The only limited sharing exception is a question–answer pair the user explicitly elects to attach to feedback. Remove direct identifiers from attachments and free-text notes; the expert-facing record must not expose sender identity or allow browsing the rest of the conversation.
- An unchecked attachment option must result in no conversation content being shared. Admin endpoints must not expose expert feedback content.
- Do not log passwords, health profiles, chat content, prompts, answers, or health-bearing request bodies. Log operational metadata only.
- Send the AI service only the necessary question/context after removing unnecessary identity information; never include account name, email, or password. See ambiguity A3 concerning the wording of M-11.
- Deletion requires confirmation. Account deletion also requires email confirmation and removes related personal data from the primary database; ending a session or hiding a record is insufficient.
- Define how deletion affects feedback copies, backups and restore procedures before claiming complete deletion compliance. Do not restore deleted users' data accidentally.

The SRS cites Vietnamese personal-data legislation and requires source-permission checks. This handoff records those project requirements; it does not independently verify current law or any website's collection permissions.

## 4. Functional coverage and implementation priority

S1 defines **Cao** as mandatory for the MVP and **Trung bình** as work after the high-priority functions. S3 assigns 11 UC high priority and 8 medium priority. A high-priority UC can contain a medium-priority subfunction; preserve FR-level priority rather than assuming every branch has the same priority.

| UC | Goal | FR | UC priority | Key boundary |
| --- | --- | --- | --- | --- |
| UC-01 | Đăng ký tài khoản | FR-1.1, FR-1.3 | Cao | Email/password or configured Google/Facebook; adulthood and consent. |
| UC-02 | Đăng nhập, đăng xuất | FR-1.1, FR-1.2 | Cao | Reject locked accounts, end sessions; password recovery is medium-priority FR-1.2. |
| UC-03 | Quản lý tài khoản cá nhân | FR-1.2, FR-1.4 | Cao | Edit own account, change password, email-confirmed account/data deletion. |
| UC-04 | Quản lý hồ sơ sức khỏe | FR-2.1 | Trung bình | Self-entry, units, validation; BMI only with valid height and weight. |
| UC-05 | Ghi nhận và xem thống kê chỉ số | FR-2.2 | Trung bình | Daily weight/sleep, weekly/monthly views, non-diagnostic trend comments. |
| UC-06 | Trò chuyện với trợ lý AI | FR-2.3 | Cao | Safety Gate, clarification, approved-source RAG, checked answer. |
| UC-07 | Đánh giá mức độ cần chú ý | FR-2.4 | Cao | Included by UC-06; system evaluates rules and suggests specialty/directory entries. |
| UC-08 | Đánh giá câu trả lời | FR-2.8 | Trung bình | Extends UC-06; optional anonymous attachment, never automatic chat sharing. |
| UC-09 | Quản lý lịch sử hội thoại | FR-2.3 | Cao | List, reopen, retain or permanently delete own sessions with confirmation. |
| UC-10 | Tra cứu kiến thức sức khỏe | FR-2.5 | Cao | Public search/read of approved content with source and dates. |
| UC-11 | Tra cứu cơ sở y tế | FR-2.9 | Trung bình | Public directory search by specialty, province/city or name; no booking. |
| UC-12 | Chọn lộ trình sinh hoạt | FR-2.6 | Trung bình | Preview, apply or switch approved templates; allergy and eligibility filtering. |
| UC-13 | Theo dõi thói quen | FR-2.7 | Trung bình | Persist daily completion and calculate streak; duplicate-safe updates. |
| UC-14 | Thiết lập nhắc nhở | FR-2.7 | Trung bình | User-set time/content, Web Push or email, toggle off, duplicate-safe sending. |
| UC-15 | Quản lý bài viết và mẫu kế hoạch | FR-3.1 | Cao | Draft, edit, delete eligible drafts, submit for review; see A1. |
| UC-16 | Quản lý quy tắc cảnh báo | FR-3.2 | Cao | R-03 authors sourced conditions and clarification fields; draft rules are inactive. |
| UC-17 | Rà soát và phê duyệt nội dung | FR-3.1, FR-3.2, FR-3.5 | Cao | R-03 approves/returns content; feedback processing under FR-3.5 is medium. |
| UC-18 | Quản lý người dùng và phân quyền | FR-3.3 | Cao | R-04 searches/locks accounts and grants/revokes expert status. |
| UC-19 | Xem thống kê hệ thống | FR-3.4 | Trung bình | Aggregate registrations, visits and chatbot interactions; no chat content. |

FR-1.1, 1.3, 1.4, 2.3, 2.4, 2.5, 3.1, 3.2 and 3.3 are high priority. FR-1.2, 2.1, 2.2, 2.6, 2.7, 2.8, 2.9, 3.4 and 3.5 are medium priority.

The standalone directory page is medium priority, but selecting verified facility entries inside a care-direction response belongs to UC-07. Seed a small directory early enough to support that high-priority path.

Preserve the detailed flows' empty, cancel, validation, permission-denied and service-failure states. Examples: no future-dated measurements; one measurement record per user/day; one completion state per user/activity/day; no invented trend when a period has no data; invalid/expired/reused confirmation tokens cannot change an account; failed storage must not display success.

## 5. AI behavior contract

### Five care-direction labels

| Label | Intended behavior from S1 Table 2.2 |
| --- | --- |
| `EMERGENCY_NOW` | Immediately display the approved instruction to call 115 or go to the nearest medical facility; stop ordinary advice. |
| `SEEK_CARE_SOON` | Recommend contacting a medical facility within a suitable timeframe defined by the approved rule; suggest specialty. |
| `ROUTINE_CONSULT` | Recommend arranging consultation for persistent/recurrent symptoms; suggest specialty. |
| `GENERAL_SELF_CARE_INFO` | Provide sourced general care information and warning signs requiring care. |
| `INSUFFICIENT_INFO` | Ask for necessary missing information and avoid a conclusion. |

`OUT_OF_SCOPE` appears in S4, but **is not a sixth care level in S1**. Refusal of drug/dose/diagnosis requests remains required. If an implementation uses a separate routing/refusal status, keep it separate from the five-level care enum. Likewise, a rule-store failure is a technical failure status, not evidence of low risk.

### Required message flow

1. Check authentication/consent, accept the message, and extract only facts the user actually supplied: symptoms, onset/time, progression and accompanying signs. An unmentioned sign is **unknown**, not absent (BR-01).
2. Run approved warning rules first. An emergency match displays the approved emergency response immediately, without a generative advice call or delay for follow-up questions (BR-02).
3. Otherwise identify missing critical fields and ask clarification when necessary (BR-03). Re-evaluate the rules after each user reply; new facts may change the result.
4. The rules determine the care direction. The LLM cannot choose a weaker level or override that result (BR-04).
5. Retrieve suitable, approved document chunks for knowledge questions. No relevant evidence means an explicit limitation, not an unsupported medical answer (BR-05, BR-11).
6. Select any specialty and facility from approved mappings/the verified directory. Never generate facility names, contacts or addresses (BR-06).
7. Have the LLM express the response using minimal necessary context. Check output format, citations, prohibited diagnosis/drug/dose content and adherence to the rule result before display (BR-07, BR-09, BR-10).
8. Show the checked response with real sources and the reference-only disclaimer; save authorized history. If AI fails, retain available rule guidance, report the failure and allow retry (BR-08).

BR-12 applies throughout: only expert-approved articles, templates and rules can be used. Each has source name, URL, verification date and review status. Multiple matching rules use a configured safer-action precedence. Missing rules or an unavailable rule store must not default to `GENERAL_SELF_CARE_INFO`.

**Proposal:** represent symptom facts with `present / absent / unknown`, and preserve relevant facts across the current session without resending full history. Return structured fields such as care level, matched rule IDs/version, missing fields, verified specialty/facility IDs, evidence IDs, answer text and processing status. Schema names are design choices. Do not add a disease-prediction field or a numeric clinical risk score.

**Streaming constraint:** S1 requires both streaming and output checks before display. Do not expose raw unchecked provider tokens. See A2 for a proposed buffering approach and the measurement issue that still needs resolution.

## 6. Data responsibilities and separation

S1 §2.3.1 explicitly separates three knowledge stores. Lifestyle templates and personal operational records have separate responsibilities as well.

| Store | Required purpose and source fields | Handling |
| --- | --- | --- |
| RAG documents and chunks | Community-facing approved articles; topic, content, source name/URL, update and verification dates, copyright notes, review status. | Extract text, review, then index approved versions. Chunks retain their parent document and source. |
| Warning rules | Symptom group, trigger conditions, required information, one of five levels, specialty, reference, reviewer/date. | Manually structure and review. A prose article or an LLM draft is not automatically an active rule. No predicted-disease field. |
| Facility directory | Name, specialties, address, official website, public contact, source and verification date. | Small team-preloaded directory, used for both chat suggestions and public search. S1 does not require a directory-import management screen. |
| Lifestyle templates | Approved goal, activities, duration, eligibility/exclusions, allergy-related elements, sources and review state. | Curate templates; select/filter them rather than generate personalized treatment plans. |
| Personal application data | Accounts/roles/consents, health profile, daily metrics, sessions/messages, feedback, selected plans, completions and reminders. | Server-side persistence with ownership controls and deletion; never add private records to the shared RAG corpus. |
| Evaluation data | Synthetic Vietnamese scenarios, expected outcomes, supporting references and prohibited behavior. | Separate from RAG documents, prompts used as examples, and development tuning cases. |

**Proposal:** use stable IDs, content hashes and explicit versions to connect source pages, approved content, rules, chunks and evaluation evidence. A useful review lifecycle is `draft → pending_review → approved`, with returned/retired states as needed. Editing approved content requires review before the new version is exposed. Retired or superseded chunks must disappear from retrieval and caches.

## 7. Small data collection plan

This section is a **proposal tailored to the owner's personal-computer constraint**. S1 specifies limited topics and official sources, but does not prescribe page counts, chunk sizes, storage budgets or crawler settings. These defaults are ceilings and starting targets, not minimum quotas. Quality and permission checks take priority over filling a count.

### Start with a narrow collection

- Use S1's example topics, seasonal influenza and dengue, plus a small sleep-hygiene collection to support one lifestyle demonstration. Confirm the actual topic list during design; do not claim general medical coverage from this sample.
- Prefer Vietnamese public education pages from WHO Viet Nam, the Ministry of Health and official medical institutions, as specified in S1. Verify exact URLs and collection permission when collection begins. No live permissions or links have been verified here.
- Begin from manually selected URLs. Avoid unrestricted recursive crawling or downloading entire sites.
- Treat public knowledge, rule references and facility facts as separate outputs, even when a page supports more than one.

| Artifact | Pilot | Small demo target | Upper limit for initial collection |
| --- | --- | --- | --- |
| Knowledge articles | 10 approved candidates | 20–40 useful approved articles | 60 retained article pages |
| Facility records | 5 verified entries | 10–15 entries in one selected city/province | 20 retained facility-source pages; 15 facility records |
| Warning rules | Enough reviewed rules to exercise all five levels | Roughly 10–20 reviewed rules if coverage supports it | Do not increase volume just to hit a quota |
| Lifestyle templates | 1–2 reviewed sleep templates | 4–8 across supported goals, when medium-priority work starts | 8 templates initially |
| RAG chunks | Measure after cleaning | Roughly 200–600 | 1,000 approved chunks |
| Final evaluation | Draft structure and labeling process | 120 synthetic cases required by S1 | Preserve all 120; these are not crawled pages |

Counts refer to unique retained content pages. Rules/templates are curated records and can reuse references; they are not extra reasons for a broad crawl. Start with the pilot and increase only to fill a demonstrated coverage or retrieval gap.

### Enforce collection and storage limits

**Proposed initial batch configuration:**

```yaml
crawl_mode: explicit_url_allowlist
max_retained_content_pages: 80   # <=60 article pages + <=20 facility pages
max_http_requests: 200           # includes discovery, redirects, robots checks, retries
max_cumulative_download_mib: 150 # includes discarded bodies and retries
max_data_directory_mib: 250      # raw + cleaned + index + manifests + temporary files
concurrency: 1
min_request_interval_seconds: 3  # obey a stricter source policy when present
request_timeout_seconds: 20
max_retries_per_url: 1
max_response_body_mib: 2
allowed_content_types:
  - text/html
  - text/plain
download_images: false
download_video_or_audio: false
download_pdfs: false             # add a few selected text PDFs only if actually needed
max_approved_chunks: 1000
auto_expand_scope: false
```

Stop at whichever limit is reached first and report the reason. Count bytes while streaming; do not download a large response fully before checking its size. The request and byte budgets apply to the whole initial collection, including resumed runs, rather than resetting on every restart. Persist the counters. Any enlarged budget should be an explicit, recorded change.

Check robots.txt, terms and copyright for each source **before** fetching content. These checks are distinct: robots permission does not grant copying rights. Honor stricter rate limits, do not bypass blocks/login/CAPTCHA, and stop or back off on blocking/rate-limit responses. If reuse rights are unclear, retain only permitted metadata/link information and exclude unsupported full text from RAG until resolved.

Prefer text-first HTTP extraction. Avoid headless browser crawling, scanned PDFs/OCR, media archives, and unneeded historical copies in the initial batch. If an essential page requires another format, assess that individual page rather than enabling a site-wide download.

### Processing and review workflow

1. Build a manifest of candidate URLs with topic, intended store, source organization and permission-check result.
2. Fetch only allowed URLs with explicit request/byte caps and resumable status tracking.
3. Remove navigation, advertisements and boilerplate. Preserve headings, warnings, context and source dates; keep Vietnamese text intact.
4. Canonicalize URLs and deduplicate by URL and content hash. Review near-duplicate articles instead of indexing every copy.
5. Mark incomplete, conflicting, outdated, identity-bearing or scope-inappropriate material for review. Do not convert an unknown publication date into an invented date.
6. Obtain content-expert review; record reviewer, decision and date. Never mark material approved merely because a crawler downloaded it.
7. Chunk approved text by section. **Proposed starting point:** 300–500 tokens, about 50-token overlap; retain headings/source IDs and avoid splitting warnings from their context. Tune only after retrieval evaluation.
8. Build one small local retrieval index. Reprocess only changed approved documents and reuse hashes/embeddings.
9. Verify that every indexed chunk belongs to an approved current document and every citation resolves to its actual source.
10. Keep a compact collection report: requested/fetched/accepted/rejected/approved counts, rejection reasons, bytes, chunk count and topic coverage. Retain at most one permitted raw copy per page; clean temporary files and avoid duplicate indexes.

The data-directory limit excludes application dependencies and any local model weights. Track those separately; they can dominate disk use. Neither the budget nor the article count is a measured estimate of this computer's capacity.

### Public datasets mentioned in S1

ViMQ is a reference for Vietnamese intent/entity recognition; MedQuAD is an English NIH-source QA reference requiring filtering; ViHealthQA is research-only in the SRS and requires personal-information filtering. Their current licenses and accessibility are not verified here.

**Proposal:** skip bulk downloads of all three initially. They are optional references, not a prerequisite to the MVP and not automatically approved RAG content. If a later experiment needs one, check its actual terms, choose a small permitted subset and document the purpose. English QA translation and model fine-tuning are outside the initial data work. Never use real patient records as evaluation data.

## 8. Compute-conscious architecture guidance

S1 describes a layered web application with replaceable AI integration. Gemma is tentative. S1 §1.8 discusses an external model API, §2.3.7 leaves the final stack open, and the sequence diagrams show React, PostgreSQL, backend controllers/services and bearer authentication. Treat these as existing design indications; confirm them against the team's final design before locking dependencies.

**Proposals for the personal-computer MVP:**

- Begin with one modular backend, one frontend and one database. Keep Safety Gate, retrieval, output validation and provider integration as clear modules; avoid a microservice/cluster setup.
- Evaluate the planned model through an API early if available and affordable. This avoids assuming local GPU memory. A small local quantized model is an option only after actual RAM/VRAM, disk use, Vietnamese output and latency have been measured.
- No training from scratch or fine-tuning is needed for the initial MVP. RAG, approved rules and curated templates implement the described design.
- Start retrieval with a simple lexical baseline; add a single compact multilingual embedding index only if evaluation justifies it. Measure on Vietnamese queries and retain source filtering in either case. A heavyweight vector service is unnecessary for a few hundred chunks.
- Embed in small batches on CPU or through a permitted service, cache by content hash and keep only one active index. Avoid rerankers and multiple embedding-model copies initially.
- Retrieve at most five relevant chunks as a starting point, consistent with evaluating Hit@5. Limit conversation context and generated length with configurable values; do not resend an entire health profile/history.
- Use one scheduler for reminders and retries. Persist job state, prevent duplicate sends and let users turn reminders off.
- Keep AI/provider adapters replaceable, with timeouts and bounded retries. Mocked responses can support development but cannot establish model quality, latency or SRS acceptance.
- Set an explicit API spending/request limit during design; the sources contain no agreed monetary budget.

Do not remove ownership checks, consent, rule review, output checks or the final evaluation suite to save compute. Reduce corpus breadth and optional infrastructure first.

## 9. Nonfunctional requirements to preserve

These are the **22** NFR in S1 Table 2.4, not the 14 in S4.

| NFR | Requirement summary |
| --- | --- |
| NFR-01 | Ordinary actions respond within 3 seconds in the test environment; show processing/success/failure for writes and deletion. |
| NFR-02 | Immediate answering state; streaming; first response text within 10 seconds; timeout and retry behavior. |
| NFR-03 | External service failure leaves independent features available; understandable error states. |
| NFR-04 | Type/format/required-field validation; repeated submissions do not duplicate records. |
| NFR-05 | bcrypt/Argon2 password hashing, expiring sessions, logout invalidation and limited failed login attempts. |
| NFR-06 | Backend role/ownership checks for every API; no unauthorized disclosure. |
| NFR-07 | HTTPS, environment-held secrets, SQL injection/XSS protection, no internal details in errors. |
| NFR-08 | Data minimization and consent; no health/chat/password content in logs. |
| NFR-09 | Users control their own data; confirmed permanent deletion from the database. |
| NFR-10 | Safety Gate before LLM; immediate emergency direction; no diagnosis/prescribing/treatment-stop advice. |
| NFR-11 | Understandable Vietnamese, grounded content, real citations and reference-only disclaimer. |
| NFR-12 | Minimum necessary AI payload, no name/email/password; handle service errors, refusals and rate limits. |
| NFR-13 | Consistent UI and messages, field-specific validation and simple common actions. |
| NFR-14 | Readable contrast/type; alerts use text and symbols, with WCAG 2.1 AA as reference. |
| NFR-15 | Stable Chrome, Edge and Firefox; desktop/tablet/phone layouts. |
| NFR-16 | Separate presentation, business, data and AI integration layers; replace model without rewrite. |
| NFR-17 | Shared code conventions, Safety Gate/core business tests, setup README, API/database change records. |
| NFR-18 | Periodic database backup script; backups not public. |
| NFR-19 | Documented restore procedure, actually exercised in testing. |
| NFR-20 | Validate external responses, set timeouts and keep retries duplicate-safe. |
| NFR-21 | Restricted admin access to operational logs containing time, error type, correlation ID and latency. |
| NFR-22 | Vietnamese UI/content, dd/mm/yyyy dates, 24-hour times and explicit measurement units. |

## 10. Evaluation and acceptance

### Required 120-case dataset

S1 Chapter 3 requires synthetic Vietnamese cases. Each records the expected care direction where applicable, related documents and prohibited answer behaviors. Two team members label independently against official sources. The set is usable only after M-06 is met.

| Category | Cases | Metrics |
| --- | --- | --- |
| Emergency warning signs | 30 | M-01, M-02 |
| Other four care levels | 30 | M-02 |
| Knowledge questions supported by the corpus | 40 | M-03, M-04 |
| Drug/diagnosis requests and other scope attacks | 20 | M-05 |
| Total | 120 | Other metrics use their appropriate surveys, checks or review samples. |

**Proposal:** use 24 development smoke cases, for example 6/6/8/4 across these categories, separately from the final 120. This is an extra small development set, not a replacement or partition that shrinks the final evaluation. Avoid placing final evaluation answers/questions in the RAG index or few-shot prompts. Freeze the final cases and corpus/rule versions before formal scoring; record any later changes and reruns.

**Proposed case fields:** ID, category, synthetic message/context, labeler-1/labeler-2 decisions, adjudicated care label if applicable, expected routing/refusal behavior, relevant document and rule IDs, official evidence, forbidden behaviors and review status. An out-of-scope case can expect refusal without assigning a sixth care level.

### SRS metric thresholds

| Metric | Required threshold | Interpretation |
| --- | --- | --- |
| M-01 Emergency recall | ≥ 90% | TP/(TP+FN) on the 30 emergency cases; at least 27/30 meets the numeric threshold. This is a demo target, not proof of clinical safety. |
| M-02 Care-direction classification | Accuracy ≥ 75%; Macro-F1 ≥ 0.70 | Five labels, evaluated on the 60 care-direction cases. |
| M-03 Retrieval | Hit@5 ≥ 0.75; MRR ≥ 0.55 | Use the 40 supported knowledge questions and a documented relevant-document mapping. |
| M-04 Source faithfulness | ≥ 0.80 | Supported claims / all evaluated claims; review actual evidence, not just presence of a URL. |
| M-05 Scope violations | ≤ 2% | Also apply S1 §3.3: even one prescription or asserted diagnosis is a serious failure. See A4 on denominator/minor-count interpretation. |
| M-06 Labeler agreement | Cohen's κ ≥ 0.61 | Compute from independent labels before resolving disagreements; prerequisite to using the case set. |
| M-07 Answer rubric | Mean ≥ 3.5/5 | Safety, correct sourcing and clarity. |
| M-08 Usability | SUS ≥ 68 | Use real demo participant responses; no fabricated survey results. |
| M-09 Latency | Ordinary actions ≤ 3 seconds; first chatbot text ≤ 10 seconds | Log actual observed timings. S3 additionally calls this p90; see A5. |
| M-10 Security checks | ≥ 90% pass; no ZAP High alerts | Auth, ownership, injection and other defined security checks. |
| M-11 Sensitive-data leakage | 0 | Check logs and AI payloads; resolve the health-context wording conflict in A3 before claiming a pass. |
| M-12 Main-flow compatibility | ≥ 90% pass | Denominator is flow × browser × screen-size combinations, not just distinct flows. |

NFR-03, 04, 09, 14, 16–20 and 22 also need explicit pass/fail acceptance checklists, as stated in S1. Measure observed results; never present target thresholds as achieved scores. Any emergency miss needs investigation and documentation even if M-01 remains numerically above 90%.

## 11. Conflicts and open design decisions

### Superseded statements in the older Markdown

| Topic | S4 older summary | Use S1 instead |
| --- | --- | --- |
| Document structure | IEEE 830-style v0.2 outline | Current three-chapter report and its actual section numbers. |
| Care labels | Six labels including `OUT_OF_SCOPE` | Five care levels; separate scope refusal behavior. |
| NFR and metrics | 14 NFR and 8 metrics | 22 NFR and 12 metrics. |
| Emergency and violation thresholds | M-01 = 100%, M-02 = 0% scope violations as blocking gates | M-01 ≥ 90%; M-02 is care-direction accuracy/Macro-F1; M-05 is scope violation rate with the serious-failure rule. |
| Timeline | Eight weeks, 26/10–20/12/2026; references a separate timeline workbook | S1 confirms eight weeks and design review on 22/11. The timeline workbook was not supplied; the exact start/end dates remain supporting context from S4. |

### Ambiguities inside the main SRS

These cannot be resolved by simply preferring S1 over the other files. Record the final decision with the team. Other work can proceed without inventing an answer.

| ID | Evidence and unresolved issue | Working treatment |
| --- | --- | --- |
| A1 Admin content editing | S1 §1.5 broadly includes admin content management, and S3 permits R-04 in UC-15. S1's detailed UC-15 actor/preconditions specify a content expert. | Preserve R-03 authoring/approval. Treat R-04 authoring as unresolved; do not grant it silently. Approval and feedback review remain expert-only. |
| A2 Streaming and validation | NFR-02 requires streaming with first text within 10 seconds; BR-07 requires checks before display. The chat diagram checks the provider response before sending the checked text to the client. | Proposal: buffer provider output, validate the complete short answer, then deliver checked text incrementally. Measure time until the first checked answer text. This preserves pre-display checking, but may miss latency and differs from live token streaming; document and agree the design. A typing indicator alone does not satisfy first-answer-text latency. |
| A3 M-11 versus required health context | M-11 literally counts health content in both logs and AI payloads as leakage, while NFR-12 and §2.3.6 permit the necessary health question/context to be sent to the AI. | Logs must contain no health content; identifiers must not be sent to AI. Before external health prompts or acceptance scoring, clarify whether explicitly consented, minimized health context is an intended exception. Develop with synthetic prompts and report this metric as unresolved, not silently passed. |
| A4 M-05 denominator | Table 3.1 assigns 20 out-of-scope cases to M-05; Table 3.2 defines violations/all answers; §3.3 allows at most two minor violations and zero serious ones. | Freeze the evaluated answer population before scoring. Report violation counts, denominators and severity, including the 20-case subset. Two violations in 20 answers is 10%, not 2%; do not use the absolute count to bypass the percentage threshold. |
| A5 Latency percentile | S3 calls M-09 p90, while S1 describes response latency and the 3/10-second limits without prescribing a percentile. | S1 governs. Report per-request samples and useful distribution summaries; do not claim a p90-only pass establishes the SRS limits without an agreed measurement protocol. |
| A6 Stack choices | Embedded S1 diagrams name React/PostgreSQL and API routes, while §2.3.7 leaves frontend/backend/database selection for design. | Treat the diagrams as design indications, not proof a stack has been formally selected. Confirm and document the implementation stack. |
| A7 Missing final policies | Hardware capacity, AI provider/cost cap, exact supported topics/city, medical reviewer availability, content refresh cadence, backup retention/deletion handling and detailed rule thresholds are not finalized in the supplied files. | Keep assumptions explicit. Do not invent medical thresholds, reviewer approvals, budget commitments, or nationwide directory coverage. |

The UC-03 sequence diagram shows accepting a deletion request but omits the complete email-confirmation/deletion sequence described in its text. Implement the full textual requirement; request acceptance alone is not completed deletion. Similarly, simplified diagrams must not erase clarification or service-failure branches from the detailed UC.

## 12. Suggested order of work for Astra

This is a **proposal**, preserving S1's priority order and its design-review milestone on 22 November 2026.

1. **Confirm design assumptions:** record hardware and disk budget, selected stack/provider, narrow topic list, small directory region and the ambiguities above. Keep a decision log with SRS references.
2. **Run a small technical spike:** synthetic Vietnamese prompts, one approved rule path, a few approved documents, checked-answer delivery and timing. Verify model/API feasibility before scaling content or committing to local inference.
3. **Prepare the pilot dataset:** manifest, permission checks, bounded collection, cleaned articles, reviewed rules and a preloaded verified directory. Establish the two-person labeling workflow immediately.
4. **Deliver the high-priority application path:** registration/consent, authentication, personal account/deletion, private chat/history, rule-controlled care direction, approved knowledge search, content/rule review and account-role administration. Test ownership and failure handling alongside implementation.
5. **Complete the mandatory evaluation and operational checks:** 120 independently labeled cases, all 12 metrics as applicable, NFR checklist, browser/device coverage and an actual backup/restore exercise. Keep unresolved metrics visible.
6. **Add medium-priority functionality after the core works:** password-management branches, profile/measurements, templates/plans, habit streaks, reminders, ratings/anonymous review, standalone directory search and statistics. Prioritize useful complete flows within this group.

The data work can proceed alongside analysis and design. A smaller approved corpus is acceptable; silently dropping consent, safety checks, deletion or the required 120 final evaluation cases is not.

## 13. Ready-to-use context prompt

> Read `docs/requirements/PROJECT_CONTEXT.md` and use it as consolidated context for the SE214 Group 5 AI Health Assistant. The original `SRS – Trợ lý Sức khỏe AI (Nhóm 5).docx` is the main authority when supplied files conflict. Preserve the Vietnamese adult-only web scope, five rule-controlled care levels, approved-source RAG, privacy boundaries, 19 UC, 18 FR, 22 NFR and 12 metrics. Work on a personal-computer budget with small, explicitly bounded collection; no broad crawl or training requirement. Distinguish established requirements from proposals and unresolved decisions. Consult the source sections before implementing an ambiguous flow. Continue with the specific design or implementation task I give next.

Historical note: this context was prepared before the baseline was requested. The baseline now follows the six-module split in docs/MODULES.md. Live crawling and clinical implementation are still not part of the scaffold.
