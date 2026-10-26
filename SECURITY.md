# Security policy

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub instead:
[Report a vulnerability](https://github.com/Ysweb3/ColdStart/security/advisories/new).

Say what you found, how to reproduce it, and which commit or version you tested.

## What counts

coldstart runs a small server on `127.0.0.1:8765` for the browser extension, and it starts
programs and opens URLs on resume. Things worth reporting:

- A web page, another user or another machine reaching that server or getting past its origin check.
- A saved project or a message on the server making coldstart run a program or open a URL it
  shouldn't.
- Project data (`%APPDATA%\Coldstart\projects.json`) leaking anywhere off the machine.

For the extension, report in
[Ysweb3/ColdStartExtension](https://github.com/Ysweb3/ColdStartExtension/security/advisories/new).

## Supported versions

Only the latest commit on `main`.
