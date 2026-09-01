package za.co.claims.processing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.claims.processing.entity.OutboxEvent;
import za.co.claims.processing.enums.OutboxStatus;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
