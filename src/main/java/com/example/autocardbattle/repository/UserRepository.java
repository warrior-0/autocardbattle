package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
    // JpaRepository<엔티티타입, ID타입>을 상속받으면 
    // 기본적으로 findById, save, delete 등의 메서드를 바로 사용할 수 있습니다.
}
