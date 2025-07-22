package com.gaebang.backend.domain.point.entity;

import com.project.stock.investory.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "points", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "version"})
})
public class Point {

    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "point_seq")
    @SequenceGenerator(name = "point_seq", sequenceName = "point_sequence", allocationSize = 1)
    @Column(name = "point_id")
    private Long pointId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 받은 포인트 금액(양수 / 음수)
    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointType type;

    // 누적 입금 합계
    @Column(name = "deposit_sum", nullable = false)
    private Integer depositSum;

    // 누적 출금 합계
    @Column(name = "withdraw_sum", nullable = false)
    private Integer withdrawSum;

    @Column(nullable = false)
    private LocalDateTime date;

    // 포인트가 들어오거나 나갈 경우 통장처럼 그때마다의 거래 기록을 남기기 위한 컬럼.
    // 증가 로직은 서비스에서 넣어주기!!!! 필수임~!
    @Column(nullable = false)
    private Integer version;
}