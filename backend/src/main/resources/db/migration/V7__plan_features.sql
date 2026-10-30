-- The agenda shipped with change scheduling: the launch plan no longer lists it as "em breve".
update plan set features =
    '["Agenda online sem ligação", "Agenda da equipe com bloqueios", "Equipe ilimitada com papéis", "Serviços, profissionais e horários", "Dados protegidos (LGPD)", "Atendimentos em 3 toques (em breve)", "Caixa e comissões (em breve)", "Clientes para chamar (em breve)"]'
where code = 'launch';
