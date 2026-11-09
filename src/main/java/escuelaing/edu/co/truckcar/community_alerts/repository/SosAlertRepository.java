package escuelaing.edu.co.truckcar.community_alerts.repository;

import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SosAlertRepository extends JpaRepository<SosAlert, Long> {

    Optional<SosAlert> findByClientEventId(UUID clientEventId);

    List<SosAlert> findByStatusOrderByTriggeredAtDesc(SosStatus status);

    List<SosAlert> findByStatusInOrderByTriggeredAtDesc(java.util.Collection<SosStatus> statuses);
}
