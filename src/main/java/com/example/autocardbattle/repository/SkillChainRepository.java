package com.example.autocardbattle.repository;
import com.example.autocardbattle.entity.SkillChainEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SkillChainRepository extends JpaRepository<SkillChainEntity, Long> {
    List<SkillChainEntity> findByFirebaseUidOrderByChainOrderAsc(String firebaseUid);
    void deleteByFirebaseUid(String firebaseUid);
}
