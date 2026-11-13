-- =============================================================================
-- V6 - SOS sin ubicación y cancelación por el conductor
-- Un SOS nunca se rechaza por no traer GPS: la ubicación pasa a ser opcional y location_source dice de dónde salió
-- (DEVICE_GPS, LAST_KNOWN o UNKNOWN). El conductor puede anular una falsa alarma (CANCELLED).
-- =============================================================================

ALTER TABLE sos_alerts ALTER COLUMN latitude DROP NOT NULL;
ALTER TABLE sos_alerts ALTER COLUMN longitude DROP NOT NULL;

-- Los SOS anteriores siempre traían la ubicación del dispositivo.
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS location_source VARCHAR(20) NOT NULL DEFAULT 'DEVICE_GPS';
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS location_recorded_at TIMESTAMPTZ(6);
ALTER TABLE sos_alerts DROP CONSTRAINT IF EXISTS sos_alerts_location_source_check;
ALTER TABLE sos_alerts ADD CONSTRAINT sos_alerts_location_source_check
    CHECK (location_source IN ('DEVICE_GPS', 'LAST_KNOWN', 'UNKNOWN'));
-- Con fuente conocida hay coordenadas; sin ellas, la fuente es UNKNOWN.
ALTER TABLE sos_alerts DROP CONSTRAINT IF EXISTS sos_alerts_location_consistency_check;
ALTER TABLE sos_alerts ADD CONSTRAINT sos_alerts_location_consistency_check
    CHECK ((location_source = 'UNKNOWN' AND latitude IS NULL AND longitude IS NULL)
        OR (location_source <> 'UNKNOWN' AND latitude IS NOT NULL AND longitude IS NOT NULL));

ALTER TABLE sos_alerts DROP CONSTRAINT IF EXISTS sos_alerts_status_check;
ALTER TABLE sos_alerts ADD CONSTRAINT sos_alerts_status_check
    CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED', 'CANCELLED'));
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ(6);
ALTER TABLE sos_alerts ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(500);
