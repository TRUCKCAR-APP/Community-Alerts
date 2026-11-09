package escuelaing.edu.co.truckcar.community_alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.dto.request.CreateRoadAlertRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.OfflineBatchSyncRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.response.RoadAlertResponse;
import escuelaing.edu.co.truckcar.community_alerts.dto.response.SyncSummaryResponse;

import java.util.List;

public interface IRoadAlertService {
    RoadAlertResponse createAlert(CreateRoadAlertRequest request);
    /** Suma la confirmación de userId; AlertAlreadyConfirmedException si ya confirmó o es el autor. */
    RoadAlertResponse confirmAlert(Long alertId, String userId);
    List<RoadAlertResponse> getNearbyAlerts(double latitude, double longitude, double radiusMeters);
    List<RoadAlertResponse> getAllActiveAlerts();
    SyncSummaryResponse syncOfflineBatch(OfflineBatchSyncRequest batchRequest);
    RoadAlertResponse resolveAlert(Long alertId, String userId, String resolutionNotes);
}