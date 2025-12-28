package com.example.autocardbattle.repository;
import com.example.autocardbattle.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, String> {
    boolean existsByUsername(String username);
}
