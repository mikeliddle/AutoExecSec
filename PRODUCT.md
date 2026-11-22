# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

A person scheduling appointments for others, preparing recipients and tomorrow's schedule on their Android phone.

## Product Purpose

Auto ExecSec prepares editable People and Schedule tables, explicitly approved appointment reminders and separate private SMS Announcements through the selected phone SIM. Appointment reminders normally send two hours before appointments. Prompt the user the night before to prepare a schedule that the app cannot infer.

## Operating Context

Manual foreground paste or user-selected CSV from a sensitive source document. No source document or real personal information is bundled. The app runs locally, without paid services, network APIs, AI parsing or a backend.

## Capabilities and Constraints

Kotlin, Compose and encrypted Room. Deterministic imports, exact recipient resolution, explicit dates/time zones/country, frozen batch approval, failure and uncertainty states, explicit selected SIM, exact-alarm access and runtime SMS permissions. Android SMS is hard-restricted and requires installer allowlisting before runtime grant; device feasibility is not established by compiling an APK. Background sending must work after first unlock without requiring screen authentication per send.

## Product Principles

- Never send without exact preview and explicit batch approval.
- Never silently retry an uncertain or partially sent message.
- Keep private data local and notifications generic.
- Make permission and timing limitations visible rather than promising delivery.

## Evidence on Hand

Only synthetic CSV fixtures are bundled. [Validation evidence](docs/validation.md) describes the automated tests and limited authorized device and carrier checks; it does not establish general delivery reliability.
