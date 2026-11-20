#!/usr/bin/env bash
# Regenerates the diagrams of groups 2 to 10 from the LinkML model (docs/data-model/README.md).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/../../docs/data-model/model"
while read -r schema chapter; do
  python3 "$here/generate-diagram.py" dispatch.linkml.yaml "$schema" "$chapter" - > "../diagrams/$chapter.puml"
done <<'LIST'
run 02-instance-and-task
trace 03-trace
work 04-commission-and-return
holding 05-holding
dialog 06-dialog
reference 07-references
control 08-control-fields
payload 09-free-fields-and-content
draft 10-draft-workspace
LIST
