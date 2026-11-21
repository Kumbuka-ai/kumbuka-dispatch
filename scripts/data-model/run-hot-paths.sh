#!/usr/bin/env bash
# Runs every file under scripts/data-model/hot-paths/ as the service role with
# the tenant setting of one tenant: one warm-up run, then five runs with
# EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON); prints the median execution time,
# the buffers of the median run, and every sequential scan in the plan.
# (docs/data-model/method/measurement.md, "Hot paths".)
# Connection through the usual PG* variables; the connecting role must be able
# to SET ROLE kumbuka_dispatch.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tenant="${TENANT:-00000000-0000-4000-8000-000000000001}"
for f in "$here"/hot-paths/*.sql; do
  q="$(grep -v '^--' "$f")"
  runs=()
  for n in 0 1 2 3 4 5; do
    out="$(psql -XAtq -v ON_ERROR_STOP=1 \
      -c "SET ROLE kumbuka_dispatch" -c "SET app.tenant_id = '$tenant'" \
      -c "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $q")"
    [ "$n" -gt 0 ] && runs+=("$out")
  done
  printf '%s\n' "${runs[@]}" | python3 "$here/hot-path-summary.py" "$(basename "$f" .sql)"
done
