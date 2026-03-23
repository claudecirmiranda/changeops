-- V2__seed_test_data.sql
-- Only runs in dev/local profiles — guarded by Spring profile in application-local.yml

INSERT INTO changes (change_id, title, description, component_id, requested_by,
                     scheduled_at, status, correlation_id, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111',
   'Deploy payment-service v2.3.1',
   'Hotfix for payment timeout under peak load',
   'payment-service', 'user-001',
   NOW() + INTERVAL '2 days', 'PREPARED',
   'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   NOW(), NOW()),

  ('22222222-2222-2222-2222-222222222222',
   'Migrate auth-service to OAuth2',
   'Replace legacy session auth with JWT/OAuth2',
   'auth-service', 'user-002',
   NOW() + INTERVAL '5 days', 'COMPLETED',
   'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
   NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day'),

  ('33333333-3333-3333-3333-333333333333',
   'Database index optimisation',
   'Add composite indexes to orders table',
   'order-service', 'user-001',
   NOW() + INTERVAL '1 day', 'FAILED',
   'cccccccc-cccc-cccc-cccc-cccccccccccc',
   NOW() - INTERVAL '1 day', NOW() - INTERVAL '2 hours')
   ON CONFLICT (change_id) DO NOTHING;

INSERT INTO change_events (event_id, change_id, event_type, payload, occurred_at)
VALUES
  (gen_random_uuid(), '11111111-1111-1111-1111-111111111111',
   'ChangePreparedEvent',
   '{"changeId":"11111111-1111-1111-1111-111111111111","componentId":"payment-service"}',
   NOW() - INTERVAL '30 minutes'),

  (gen_random_uuid(), '22222222-2222-2222-2222-222222222222',
   'ChangePreparedEvent',
   '{"changeId":"22222222-2222-2222-2222-222222222222","componentId":"auth-service"}',
   NOW() - INTERVAL '3 days'),

  (gen_random_uuid(), '22222222-2222-2222-2222-222222222222',
   'ChangeCompletedEvent',
   '{"changeId":"22222222-2222-2222-2222-222222222222","deployId":"dep-222","completedAt":"2026-03-18T10:00:00Z"}',
   NOW() - INTERVAL '1 day'),

  (gen_random_uuid(), '33333333-3333-3333-3333-333333333333',
   'ChangePreparedEvent',
   '{"changeId":"33333333-3333-3333-3333-333333333333","componentId":"order-service"}',
   NOW() - INTERVAL '1 day'),

  (gen_random_uuid(), '33333333-3333-3333-3333-333333333333',
   'ChangeFailedEvent',
   '{"changeId":"33333333-3333-3333-3333-333333333333","deployId":"dep-333","reason":"Healthcheck failed after deploy"}',
   NOW() - INTERVAL '2 hours')
   ON CONFLICT (event_id) DO NOTHING;
