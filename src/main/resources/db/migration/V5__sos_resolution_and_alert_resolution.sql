-- =============================================================================
-- V5 - Resolución y atención de emergencias SOS y alertas comunitarias
-- Microservicio Community-Alerts (TruckCar)
-- =============================================================================

ALTER TABLE sos_alerts DROP CONSTRAINT IF EXISTS sos_alerts_status_check;
ALTER TABLE sos_alerts ADD CONSTRAINT sos_alerts_status_check CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED'));
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(255);
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ(6);
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS resolution_notes VARCHAR(500);

ALTER TABLE road_alerts ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP;
ALTER TABLE road_alerts ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(255);
ALTER TABLE road_alerts ADD COLUMN IF NOT EXISTS resolution_notes VARCHAR(500);
