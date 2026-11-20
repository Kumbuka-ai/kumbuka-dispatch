-- listing by a control field: the instances whose value in force of one field equals a value (DEC-0056/C22)
SELECT v.instance_id FROM (
  SELECT DISTINCT ON (a.instance_id) a.instance_id, fv.choice_value_id
  FROM dispatch.field_value fv
  JOIN dispatch.act a ON a.tenant_id = fv.tenant_id AND a.scope_id = fv.scope_id AND a.id = fv.act_id
  WHERE fv.tenant_id = '00000000-0000-4000-8000-000000000001' AND fv.scope_id = '00000000-0000-4000-9000-000000000001' AND fv.control_field_id = 7
    AND (a.kind = 'start'
         OR EXISTS (SELECT 1 FROM dispatch.act acc
                    WHERE acc.tenant_id = a.tenant_id AND acc.scope_id = a.scope_id
                      AND acc.instance_id = a.instance_id AND acc.kind = 'accept'
                      AND acc.refers_to_act_id = a.id))
  ORDER BY a.instance_id, a.sequence DESC) v
WHERE v.choice_value_id = 7
ORDER BY v.instance_id LIMIT 50;
