#!/usr/bin/env python3
import os
import re
import sys
import subprocess
import html
import argparse
from datetime import datetime

def run_cmd(cmd):
    try:
        return subprocess.check_output(cmd, shell=True).decode('utf-8').strip()
    except subprocess.CalledProcessError as e:
        print(f"Error executing command: {cmd}")
        sys.exit(1)

def main():
    # Ensure we are in the project root directory
    # Script is in change_log/auto_update.py, so root is parent of parent of this file
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    os.chdir(project_root)

    # Parse arguments
    parser = argparse.ArgumentParser(description='Auto update changelogs.')
    parser.add_argument('n', type=int, nargs='?', default=1, help='Number of git commits to include')
    args = parser.parse_args()
    num_commits = args.n

    # File paths
    gradle_file = 'build.gradle'
    html_files = ['change_log/change_log.html', 'change_log/change_log_cn.html']
    yaml_files = ['change_log/change_log_rc.yaml', 'change_log/change_log_rc_cn.yaml']

    print(f"Starting auto update process with {num_commits} commit(s)...")

    # 1. Update version in build.gradle
    if not os.path.exists(gradle_file):
        print(f"Error: {gradle_file} not found")
        sys.exit(1)

    with open(gradle_file, 'r') as f:
        gradle_content = f.read()

    # Match: def versionName = '2.6.7' or "2.6.7"
    version_pattern = r"(def\s+versionName\s*=\s*)(['\"])(.*?)(['\"])"
    match = re.search(version_pattern, gradle_content)

    if not match:
        print("Error: versionName not found in build.gradle")
        sys.exit(1)

    prefix = match.group(1)
    quote1 = match.group(2)
    current_version = match.group(3)
    quote2 = match.group(4)

    print(f"Current version: {current_version}")

    # Increment version (assuming semantic versioning X.Y.Z)
    v_parts = current_version.split('.')
    if len(v_parts) > 0 and v_parts[-1].isdigit():
        v_parts[-1] = str(int(v_parts[-1]) + 1)
        new_version = '.'.join(v_parts)
    else:
        print(f"Error: Could not parse version suffix as integer: {current_version}")
        sys.exit(1)

    print(f"New version: {new_version}")

    # Write back to build.gradle
    new_gradle_content = gradle_content.replace(
        f"{prefix}{quote1}{current_version}{quote2}",
        f"{prefix}{quote1}{new_version}{quote2}"
    )

    with open(gradle_file, 'w') as f:
        f.write(new_gradle_content)

    # 2. Get git last n commit messages
    raw_commits = run_cmd(f"git log -n {num_commits} --pretty=%s")
    commit_messages = raw_commits.splitlines() if raw_commits else []

    current_date = datetime.now().strftime("%Y.%m.%d")
    print(f"Commit messages:")
    for msg in commit_messages:
        print(f" - {msg}")

    # 3. Update HTML files
    for html_path in html_files:
        if not os.path.exists(html_path):
            print(f"Warning: {html_path} not found")
            continue

        with open(html_path, 'r') as f:
            content = f.read()

        # Update header: <h2>Version (Date)</h2>
        # We replace the first occurrence only
        header_pattern = r"<h2>.*?</h2>"
        new_header = f"<h2>{new_version} ({current_date})</h2>"

        # Check if header exists
        if not re.search(header_pattern, content):
            print(f"Warning: No <h2> tag found in {html_path}")
        else:
            content = re.sub(header_pattern, new_header, content, count=1)

        # Insert commit messages into the first <ol>
        new_lis = ""
        for msg in commit_messages:
            safe_msg = html.escape(msg)
            new_lis += f"    <li>{safe_msg}</li>\n"

        # Find the closing tag of the first ol
        ol_close_idx = content.find('</ol>')
        if ol_close_idx != -1:
            content = content[:ol_close_idx] + new_lis + content[ol_close_idx:]
        else:
            print(f"Warning: No </ol> tag found in {html_path}")

        with open(html_path, 'w') as f:
            f.write(content)

    # 4. Update YAML files
    # Build the updates list string
    yaml_updates_str = ""
    for msg in commit_messages:
        # Remove leading [xxxx] from commit message for YAML
        clean_msg = re.sub(r"^\[.*?\]\s*", "", msg)
        yaml_updates_str += f"    - {clean_msg}\n"
    new_yaml_block = f"- version: {new_version}\n  date: {current_date}\n  updates:\n{yaml_updates_str}\n"

    for yaml_path in yaml_files:
        if not os.path.exists(yaml_path):
            print(f"Warning: {yaml_path} not found")
            continue

        with open(yaml_path, 'r') as f:
            old_content = f.read()

        with open(yaml_path, 'w') as f:
            f.write(new_yaml_block + old_content)

    print("Auto update finished successfully.")

if __name__ == "__main__":
    main()
