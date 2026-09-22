package com.transformersas.marketplace.cart;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // NULL only for legacy data whose owner cannot be established safely.
    @Column(name = "account_id")
    private Long accountId;
}