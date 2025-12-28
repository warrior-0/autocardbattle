package com.example.autocardbattle.repository;
import com.example.autocardbattle.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryEntity, Long> {
    List<InventoryEntity> findByFirebaseUid(String firebaseUid);
    Optional<InventoryEntity> findByFirebaseUidAndSkillName(String firebaseUid, String skillName);
}
