# CSV imports and message templates

## CSV imports

UTF-8 CSV, optionally with a BOM; commas, CRLF/LF and quoted fields with doubled quotes are supported. Multiline fields, duplicate headers, mismatched column counts, more than 40 data rows and files over 128 KiB are rejected. Extra columns are discarded. Every row must validate; no partial schedule approval.

People CSV (recommended):

```csv
FirstName,LastName,phone
Alex,Example,+12025550101
Mary Ann,Example,+12025550102
```

Both name fields are required; the combined full name must be 1-120 printable characters. The complete `FirstName`, including internal spaces, is preserved for message greetings. Leading/trailing whitespace is trimmed. National phone numbers default to US unless you configure another two-letter country (e.g. GB, CA, AU). Existing saved country preferences are preserved, and an explicit `+` international country code takes precedence. Service codes and extensions are rejected.

Stable internal identities are derived from exact combined name + normalized phone number. Reimporting the same person reuses their identity, including an existing legacy ID when that exact pair uniquely matches. Identical repeated rows are deduplicated. Different numbers never merge merely because names match; changed names/numbers create separate people. To correct an existing person's number/name, edit that saved person (or use their legacy ID) so pending approvals are invalidated rather than silently moved. If several saved people share the exact same name and number, import stops and asks for cleanup or an explicit legacy ID. Import validates every row before loading the table; Save people validates again and saves all rows in one transaction.

Schedule CSV (recommended):

```csv
Date,Time,Name
2027-01-12,14:00,Alex Example
2027-01-12,6:30 PM,Mary Ann Example
```

Dates: `yyyy-MM-dd`. Times: `HH:mm` (24-hour) or `h:mm AM/PM`. The phone's current time zone is captured **once for each schedule import** and stored on every preview row. The preview displays that chosen zone, including unresolved rows. Later phone-zone changes cannot reinterpret it; approval freezes the zone and appointment instant. To use a different zone, explicitly edit the preview or import again.

`Name` matches the **exact case-sensitive combined FirstName + one space + LastName**, never an ID. Duplicate names and unmatched names require a manual recipient choice, showing phone numbers and internal IDs. There is no fuzzy matching or prose interpretation.

For a shared conversation, add optional consecutive `Name2`, `Name3`, ... columns:

```csv
Date,Time,Name,Name2
2027-01-12,14:00,Alex Example,Mary Ann Example
2027-01-13,14:00,Alex Example,
```

Trailing blanks permit individual rows in the same file; internal gaps and skipped column numbers are errors. Resolve each member separately. Duplicate identities or phone numbers within a group are rejected. Configure an editable template for the **exact** recipient count in Setup; a missing count template blocks approval. Reordering the same group does not bypass duplicate protection. Overlapping recipient/appointment approvals are rejected. Changing/deleting any member cancels the entire pending group approval, never just one member. Legacy schedule CSV stays individual-only.

Legacy formats remain supported:

```csv
id,name,phone
alex,Alex Example,+12025550101
```

```csv
recipient,date,time,zone
alex,2027-01-12,14:00,America/New_York
```

Legacy IDs allow 1-64 ASCII letters, digits, hyphens or underscores; matching IDs update the same person. Legacy schedules use their explicit IANA zone or fixed offset, such as `-04:00`, and match a stable ID first, then an exact full name. Headers are case-insensitive, but a header containing both complete formats is rejected as ambiguous. Extra unrelated columns are discarded.

Existing encrypted databases migrate without deleting recipients, pending reminders or duplicate protection. Legacy people retain their previous first-word greeting until an explicit first name is supplied; existing approved messages remain frozen.

A daylight-saving gap is rejected. A repeated wall-clock time is also rejected: choose the intended explicit fixed offset (for example `-04:00` versus `-05:00` for the fall overlap). Normal appointments are frozen as instants; changing the phone's time zone does not move them. Changing the wall clock triggers reconciliation and the expiry policy.

The [People](../samples/recipients.csv), [Schedule](../samples/schedule.csv), and [group schedule](../samples/group-schedule.csv) samples use **synthetic, reserved example phone numbers** and fixed dates. Do not approve them for SMS or MMS. Change dates to future synthetic test dates for offline previews; use only separately authorized real test numbers for device sending.

## Appointment templates

For Announcement templates and their supported fields, see [Announcements](usage.md#announcements).

Supported tokens: `{FirstName}` (aliases `{firstName}` and `{Firstname}`), `{LastName}`, `{time}`, `{dayPeriod}`. Unknown tokens or unmatched braces are errors. First-name tokens join every member's first name with `, ` in preview order; a single person stays unchanged. People CSV preserves the entire first and last names, including spaces. Legacy people without an explicit first name retain the first whitespace-delimited full-name word. Time is rendered in the appointment zone and includes AM/PM.

`{LastName}` produces one surname only when **every** member has the same exact, case-sensitive explicit surname. Different or missing surnames block approval and display an actionable warning; there is no first-name fallback. Use `{FirstName}` for mixed-surname groups. Legacy people can be reimported with the simple people format or edited to add their surname; migration does not guess it. Templates not using `{LastName}` do not require matching surnames.

The individual reminder template applies to exactly one recipient. In **Setup → Appointment messages & timing**, load/set templates for other exact counts, then tap **Save preferences**. There is no automatic count fallback. Changing a count's template cancels only pending approvals using that count; lead-time changes affect all pending approvals.

Default:

```text
Hey {firstName}. This is your reminder that you have an appointment with John at {time} {dayPeriod}.
```

Example at 14:00:

```text
Hey Alex. This is your reminder that you have an appointment with John at 2:00 PM today.
```

`{dayPeriod}` is `today` for appointment times before 18:00 and `tonight` from 18:00 onward, in the appointment zone. This is a deterministic rule, not inference. To avoid misleading text, send time and appointment must fall on the **same appointment-zone date**; reduce lead time for early appointments. The preview shows the complete result before authorization. Android's selected-SIM `SmsManager.divideMessage` supplies the actual segment preview, including Unicode and extended GSM characters.
