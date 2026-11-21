"""Generate the PlantUML diagram of one group of the data model from its LinkML schema.

Usage, from docs/data-model/model/:
  python3 ../../../scripts/data-model/generate-diagram.py dispatch.linkml.yaml <schema> <chapter> - \
      > ../diagrams/<chapter>.puml
<schema> is the schema file stem (run, trace, ...), <chapter> the chapter name (02-instance-and-task).
The last argument names a file of extra PlantUML lines, or - for none.
"""
import sys, yaml
from linkml_runtime.utils.schemaview import SchemaView
root, group, title, notes_file = sys.argv[1:5]
sv = SchemaView(root)
own = yaml.safe_load(open(f"{group}.linkml.yaml"))
classes = list(own["classes"].keys())
tab = {c: sv.get_class(c).annotations["table"].value for c in sv.all_classes() if sv.get_class(c).annotations and "table" in sv.get_class(c).annotations}
out = [f"@startuml {title}", f"' {title} (docs/data-model/groups/{title}.md), generated from model/{group}.linkml.yaml.",
 "' Every table carries tenant_id and scope_id and a unique key (tenant_id, scope_id, id); every foreign",
 "' key runs over tenant, scope and id (ADR-0042). Left out below. Tables of other groups are drawn grey.",
 "skinparam shadowing false","skinparam roundcorner 0","skinparam classAttributeIconSize 0","hide methods","hide empty members",
 "skinparam class<<other>> {","  BackgroundColor #EEEEEE","  BorderColor #999999","}"]
edges, others = [], set()
for c in classes:
    cls = sv.get_class(c)
    subs = set()
    lines = ["  id : bigint"]
    for a in sv.class_induced_slots(c):
        if a.name in ("tenant", "scope"): continue
        r = a.range or "string"
        if r in tab:
            col = a.name + "_id"; t = "-> " + tab[r]
            uk = [k.unique_key_slots for k in (cls.unique_keys or {}).values()]
            one = ['tenant','scope',a.name] in [list(x) for x in uk]
            edges.append((tab[c], tab[r], a.name, a.required, one))
            if r not in classes: others.add(tab[r])
        elif sv.get_enum(r):
            col = a.name; pv = list(sv.get_enum(r).permissible_values.keys()); t = " | ".join(pv) if len(pv) <= 7 else f"{r}, {len(pv)} values"
        else:
            col = a.name; t = r
        if a.in_subset: subs.update(a.in_subset)
        lines.append(f"  {col} : {t}" + ("" if a.required else "  [0..1]"))
    st = "/".join(sorted(subs))
    out.append(f"class {tab[c]} <<{st}>> {{"); out += lines; out.append("}")
    mut = cls.annotations["mutable"].value
    out.append(f"note bottom of {tab[c]} : updates: {mut}")
for o in sorted(others): out.append(f"class {o} <<other>>")
for a, b, n, req, one in edges:
    out.append(f'{a} {"|o" if one else "}o"}--{"||" if req else "o|"} {b} : {n}')
if notes_file != "-": out += open(notes_file).read().splitlines()
out.append("@enduml")
print("\n".join(out))
