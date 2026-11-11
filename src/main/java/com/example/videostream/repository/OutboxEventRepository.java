package com.example.videostream.repository;

import com.example.videostream.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the transactional outbox.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Locks and returns the oldest unsent events. {@code SKIP LOCKED} makes a second relay (another instance,
     * or an overlapping pass) skip rows that the first one holds instead of waiting for them or publishing
     * them again. Must run inside a transaction: the locks last until it ends.
     *
     * @param batch the maximum number of rows
     * @return unsent events in id order
     */
    @Query(value = "SELECT * FROM outbox_events WHERE sent_at IS NULL ORDER BY id LIMIT :batch "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockUnsent(int batch);
}
