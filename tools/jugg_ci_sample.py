#!/usr/bin/env python3
"""Small CI orchestration sample. Replace storage and policy functions for a real CI."""
import json, shutil, subprocess
from pathlib import Path

def load_base_builds(context): return context["base_builds"]
def build_scenario_key(context): return context["scenario_key"]
def filter_compatible_base_builds(records, context): return [r for r in records if r.get("scenarioKey") == build_scenario_key(context)]
def run_jugg_command(args, cwd): return subprocess.run(args, cwd=cwd, text=True, capture_output=True, check=False)
def select_nearest_base(context, records):
    candidates = Path(context["work_dir"]) / "candidate-commits.json"; candidates.write_text(json.dumps({"commits": [r["baseCommit"] for r in records]}))
    output = Path(context["work_dir"]) / "base-result.json"
    result = run_jugg_command([context["cmd_line"], "cmd=findNearestBaseCommit", f"projectDir={context['project_dir']}", f"targetCommit={context['target_commit']}", f"candidateCommitsFile={candidates}", f"output={output}"], context["project_dir"])
    return json.loads(output.read_text()) if output.exists() else None
def filter_changed_files(document, context): return document.get("files", [])
def should_use_incremental(changed_files, context): return bool(changed_files) and all(f["type"] not in context.get("blocked_types", {"BuildFile"}) for f in changed_files)
def save_base_build(record, artifacts): context = artifacts; context["save_record"](record)
def build(context):
    records = filter_compatible_base_builds(load_base_builds(context), context)
    match = select_nearest_base(context, records) if records else None
    if not match or not match.get("matchedCommit"): return "buildGradleBase"
    return "buildIncrementalApk" if should_use_incremental(context["changed_files"], context) else "buildGradleBase"
