-- gate: the values in force of the control fields of one instance (DEC-0056/C10)
SELECT DISTINCT ON (fv.control_field_id)
       fv.control_field_id, fv.value_type, fv.boolean_value, fv.number_value, fv.date_value,
       fv.text_value, fv.choice_value_id
FROM dispatch.field_value fv
JOIN dispatch.act a ON a.tenant_id = fv.tenant_id AND a.scope_id = fv.scope_id AND a.id = fv.act_id
WHERE a.tenant_id = '00000000-0000-4000-8000-000000000001' AND a.scope_id = '00000000-0000-4000-9000-000000000001' AND a.instance_id = 1500
  AND (a.kind = 'start'
       OR EXISTS (SELECT 1 FROM dispatch.act acc
                  WHERE acc.tenant_id = a.tenant_id AND acc.scope_id = a.scope_id
                    AND acc.instance_id = a.instance_id AND acc.kind = 'accept'
                    AND acc.refers_to_act_id = a.id))
ORDER BY fv.control_field_id, a.sequence DESC;
