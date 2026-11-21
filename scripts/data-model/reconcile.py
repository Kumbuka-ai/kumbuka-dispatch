#!/usr/bin/env python3
"""Reconcile the LinkML model of the dispatch store with a migrated database (criterion R1).

Reads the conceptual and logical model (docs/data-model/model/dispatch.linkml.yaml and its imports)
and the system catalogue of a database migrated with the store line, and reports in both directions:
tables without a concept, concepts without a table, columns without an attribute, attributes without
a column. Exits 1 on any report.

Connection: the libpq environment (PGHOST, PGPORT, PGUSER, PGDATABASE). Schema: dispatch.

  python3 scripts/data-model/reconcile.py docs/data-model/model/dispatch.linkml.yaml
  python3 scripts/data-model/reconcile.py docs/data-model/model/dispatch.linkml.yaml --only-modelled

--only-modelled skips the report of tables without a concept, for a model that is still being
written group by group.
"""
import argparse
import subprocess
import sys

from linkml_runtime.utils.schemaview import SchemaView

SKIP_TABLES = {"flyway_schema_history"}


def catalogue_columns():
    out = subprocess.run(
        ["psql", "-At", "-v", "ON_ERROR_STOP=1", "-c",
         "SELECT table_name || '|' || column_name FROM information_schema.columns "
         "WHERE table_schema = 'dispatch'"],
        capture_output=True, text=True, check=True).stdout.split()
    cols = {}
    for line in out:
        table, column = line.split("|")
        if table not in SKIP_TABLES:
            cols.setdefault(table, set()).add(column)
    return cols


def model_columns(sv, cname):
    columns = {"id"}
    classes = set(sv.all_classes())
    for slot in sv.class_induced_slots(cname):
        if slot.annotations and "column" in slot.annotations:
            columns.add(slot.annotations["column"].value)
        elif slot.range in classes:
            columns.add(slot.name + "_id")
        else:
            columns.add(slot.name)
    return columns


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("schema")
    ap.add_argument("--only-modelled", action="store_true")
    args = ap.parse_args()

    sv = SchemaView(args.schema)
    cols = catalogue_columns()
    reports = []
    mapped = set()
    for cname in sorted(sv.all_classes()):
        cls = sv.get_class(cname)
        if cls.abstract:
            continue
        table = cls.annotations["table"].value if "table" in cls.annotations else None
        if not table:
            reports.append(f"concept without table: {cname}")
            continue
        mapped.add(table)
        if table not in cols:
            reports.append(f"concept {cname} names a table the store does not have: {table}")
            continue
        model = model_columns(sv, cname)
        reports += [f"{table}.{c}: column without attribute" for c in sorted(cols[table] - model)]
        reports += [f"{table}.{c}: attribute without column" for c in sorted(model - cols[table])]
    if not args.only_modelled:
        reports += [f"table without concept: {t}" for t in sorted(set(cols) - mapped)]

    for r in reports:
        print(r)
    print(f"{len(mapped)} concept(s) with a table, {len(reports)} report(s)")
    return 1 if reports else 0


if __name__ == "__main__":
    sys.exit(main())
