#!/usr/bin/env python3
"""Reads the JSON plans of the runs of one hot path from stdin and prints one
line: name | median execution ms | planning ms | shared buffers touched |
rows | sequential scans | plan nodes. Called by run-hot-paths.sh."""
import json
import sys

name = sys.argv[1]
decoder = json.JSONDecoder()
text = sys.stdin.read()
plans, pos = [], 0
while pos < len(text):
    while pos < len(text) and text[pos].isspace():
        pos += 1
    if pos >= len(text):
        break
    obj, pos = decoder.raw_decode(text, pos)
    plans.append(obj[0])
plans.sort(key=lambda p: p["Execution Time"])
median = plans[len(plans) // 2]
seq, nodes = [], []


def walk(node):
    label = node["Node Type"]
    if "Index Name" in node:
        label += " " + node["Index Name"]
    nodes.append(label)
    if node["Node Type"] == "Seq Scan":
        seq.append(node["Relation Name"])
    for child in node.get("Plans", []):
        walk(child)


walk(median["Plan"])
root = median["Plan"]
buffers = root.get("Shared Hit Blocks", 0) + root.get("Shared Read Blocks", 0)
print("|".join([name, "%.3f" % median["Execution Time"], "%.3f" % median["Planning Time"],
                str(buffers), str(root["Actual Rows"]), ",".join(seq) or "-", " > ".join(nodes)]))
