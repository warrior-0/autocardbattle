package com.example.autocardbattle.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor
public class UserEntity {
    @Id
    private String firebaseUid; // PK: Firebase에서 발급받은 고유 ID

    @Column(unique = true, nullable = false)
    private String username; // 유저 닉네임 (중복 불가)

    @Column
    private String selectedDeck;
}
