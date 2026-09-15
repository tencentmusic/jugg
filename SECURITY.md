<p align="left">
  <strong>English</strong> | <a href="./SECURITY.zh-CN.md">简体中文</a>
</p>

# Security Policy

## Supported versions

Security updates are issued for the latest stable Jugg release on GitHub.

| Version | Supported |
| --- | --- |
| [Latest GitHub Release](https://github.com/tencentmusic/jugg/releases/latest) | Yes |
| Older GitHub Releases | Please upgrade to the latest release |
| Nightly / development builds | Best-effort only |

## Reporting a vulnerability

Do not open a public GitHub issue, pull request, or discussion for a suspected security vulnerability.

Report privately by emailing [ch.operation@gmail.com](mailto:ch.operation@gmail.com). If this repository's Security tab offers **Report a vulnerability**, you may use GitHub private vulnerability reporting instead.

Please include:

- A description of the issue and why it is a security problem
- The affected Jugg version or build
- Steps to reproduce, or a minimal proof of concept
- The expected impact, such as credential exposure, unexpected code execution, or diagnostic data leakage

Do not include signing keys, production credentials, or private source beyond what is needed to reproduce the issue.

## What happens next

- We will acknowledge the report as soon as we can, typically within a few business days.
- We will tell you whether we accepted the report, declined it, or need more information.
- If accepted, we will work on a fix and coordinate public disclosure after a release is available.
- We may credit reporters who want to be named.

Please give us a reasonable window to investigate and ship a fix before any public disclosure.

## Scope

This policy covers Jugg itself: the Android Studio / IntelliJ plugin, the `jugg` CLI, MCP tools, issue-report upload, custom compiler loading, and the JVMTI agent.

It does not cover ordinary compile or deploy bugs, or defects in apps built with Jugg, unless they expose a security problem in Jugg.

For non-security bugs, use a [Bug Report](https://github.com/tencentmusic/jugg/issues/new?template=04_bug_report_en.yml).
