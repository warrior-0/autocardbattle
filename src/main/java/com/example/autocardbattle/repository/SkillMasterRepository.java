package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.SkillMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SkillMasterRepository extends JpaRepository<SkillMasterEntity, String> {
}
