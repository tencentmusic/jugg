---
title: MCP and CLI
description: Explains why Agent, CLI, and MCP are tool entry points rather than separate capability implementations, how long-running tasks reach a terminal state, and which tools are currently public.
status: active
tags:
  - concept
  - mcp
  - cli
---

# MCP and CLI

MCP and CLI are tool entry points through which an Agent or terminal accesses Jugg capabilities. They do not reimplement compilation, deployment, testing, or UI inspection. Instead, they forward requests to the initialized Jugg runtime inside the IDE plugin.

## Automation needs a stable entry point, not another implementation

When Agents and scripts invoke Jugg, a common mistake is to recreate compilation and deployment logic outside the IDE. This immediately diverges from real IDE state: the project snapshot, device state, and deployment history all live on the IDE side. An external reimplementation cannot share those states and continually falls out of alignment as Jugg evolves. Automation needs a stable, discoverable, and validated boundary rather than direct access to internal implementation details.

## Unified entry points forward to the same runtime

MCP and CLI both forward requests to the initialized Jugg runtime inside the IDE and handle only entry-point responsibilities:

```text
Agent / terminal
  -> jugg CLI
  -> local Jugg MCP endpoint
  -> registered MCP tool
  -> Jugg compilation / deployment / testing / UI runtime
```

The CLI discovers the local MCP port, resolves the project path, wraps command arguments, and polls long-running tasks. A client that uses MCP directly must handle JSON-RPC requests, schema validation, and asynchronous task completion itself.

### Only publicly registered tools can be called

MCP exposes only registered tools. The existence of a tool implementation in the code does not make it a public capability; clients can call only tools present in the tool list:

```text
MCP request
  -> schema validation
  -> projectDir initialization check
  -> find a registered tool
  -> invoke the Jugg runtime
  -> return structured results and artifacts
```

Except for tools such as `version` and `list-projects`, public tools generally require `projectDir` and require Jugg to have completed initialization in the IDE.

### Long-running task completion

Compilation, deployment, and instrumentation can return a `jobId`, indicating that the task continues in the background. The client must keep querying by `jobId` until it receives terminal results for compilation, deployment, and logs:

```text
deploy / gradle-build / instrument
  -> running + jobId
  -> poll status by projectDir and jobId
  -> terminal result and artifacts
```

The CLI already wraps this polling loop. A direct MCP client must implement it.

## Boundaries

- MCP/CLI are only tool entry points. Actual compilation and deployment behavior is defined by the corresponding capability pages.
- UI tools depend on the in-app view-tree channel and do not implicitly provide an unregistered batch layout verification capability. See [Layout dump and UI evidence](./layout-dump-and-ui-evidence.md).
- Remote diagnostics and Agent Skills wrap workflows without changing the underlying Jugg capability boundaries.

## Related pages

- [CLI guide](../guide/cli.md)
- [MCP guide](../guide/mcp.md)
- [Jugg CLI and Agent Skills](../capabilities/tools/)
- [Jugg CLI capability](../capabilities/tools/cli.md)
- [MCP for Agents](../capabilities/tools/mcp.md)
